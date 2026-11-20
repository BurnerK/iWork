package io.iwork.artifacts;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MaterialService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path originals;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;
    private final long ownerMaxBytes;
    private final long ownerMaxFiles;
    private final long instanceMaxBytes;
    private final long instanceMaxFiles;
    private final long minFreeBytes;

    public MaterialService(
            @Value("${iwork.files-directory}") Path root,
            JdbcTemplate db,
            TransactionTemplate tx,
            IdentityService identity,
            @Value("${iwork.material-owner-max-bytes:104857600}") long ownerMaxBytes,
            @Value("${iwork.material-owner-max-files:1000}") long ownerMaxFiles,
            @Value("${iwork.material-instance-max-bytes:10737418240}") long instanceMaxBytes,
            @Value("${iwork.material-instance-max-files:10000}") long instanceMaxFiles,
            @Value("${iwork.material-min-free-bytes:536870912}") long minFreeBytes)
            throws IOException {
        if (ownerMaxBytes <= 0
                || ownerMaxFiles <= 0
                || instanceMaxBytes <= 0
                || instanceMaxFiles <= 0
                || minFreeBytes <= 0)
            throw new IllegalArgumentException("Material storage limits must be positive");
        this.ownerMaxBytes = ownerMaxBytes;
        this.ownerMaxFiles = ownerMaxFiles;
        this.instanceMaxBytes = instanceMaxBytes;
        this.instanceMaxFiles = instanceMaxFiles;
        this.minFreeBytes = minFreeBytes;
        this.originals = root.resolve("originals").toAbsolutePath().normalize();
        Files.createDirectories(originals);
        this.db = db;
        this.tx = tx;
        this.identity = identity;
    }

    public record Material(
            String id, String name, String format, long byteSize, Instant createdAt) {}

    public record Page(List<Material> items, boolean hasMore) {}

    private List<Material> query(String condition, Object... args) {
        return db.query(
                "SELECT id,name,format,byte_size,created_at FROM materials WHERE " + condition,
                (r, n) ->
                        new Material(
                                r.getString("id"),
                                r.getString("name"),
                                r.getString("format"),
                                r.getLong("byte_size"),
                                r.getTimestamp("created_at").toInstant()),
                args);
    }

    public Material get(Caller caller, String id) {
        var rows = query("id=? AND owner_id=?", id, caller.id());
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        return rows.getFirst();
    }

    public Page list(Caller caller, int page) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        var rows =
                query(
                        "owner_id=? ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",
                        caller.id(),
                        page * 20);
        return new Page(rows.stream().limit(20).toList(), rows.size() > 20);
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Material upload(Caller caller, String name, long declaredSize, InputStream stream) {
        if (name == null
                || name.isBlank()
                || name.length() > 200
                || name.contains("/")
                || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw new ApiFailure(400, "invalid_file_name");
        String format = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (name.lastIndexOf('.') < 1 || !Set.of("txt", "md").contains(format))
            throw new ApiFailure(415, "unsupported_material");
        if (declaredSize > MAX_BYTES) throw new ApiFailure(413, "material_too_large");
        byte[] bytes;
        try {
            bytes = stream.readNBytes(MAX_BYTES + 1);
        } catch (IOException failure) {
            throw new ApiFailure(503, "material_storage_unavailable");
        }
        if (bytes.length > MAX_BYTES) throw new ApiFailure(413, "material_too_large");
        if (bytes.length == 0 || declaredSize >= 0 && declaredSize != bytes.length)
            throw new ApiFailure(400, "incomplete_material");
        String id = UUID.randomUUID().toString();
        return tx.execute(
                status -> {
                    // Keep admission and the original write serialized until metadata commits.
                    db.queryForObject(
                            "SELECT id FROM material_upload_gate WHERE id=1 FOR UPDATE",
                            Integer.class);
                    var live = identity.verify(caller, true);
                    if (live.mustChangePassword())
                        throw new ApiFailure(403, "password_change_required");
                    requireCapacity(caller.id(), bytes.length);
                    try {
                        writeOriginal(id, bytes);
                    } catch (IOException failure) {
                        throw new ApiFailure(503, "material_storage_unavailable");
                    }
                    // Keep a complete file after an ambiguous commit. Admission counts it on disk.
                    db.update(
                            "INSERT INTO materials(id,owner_id,name,format,byte_size,sha256) VALUES(?,?,?,?,?,?)",
                            id,
                            caller.id(),
                            name,
                            format,
                            bytes.length,
                            checksum(bytes));
                    return get(caller, id);
                });
    }

    private void requireCapacity(String ownerId, int bytes) {
        var ownerHasCapacity =
                db.queryForObject(
                        "SELECT COUNT(*) < ? AND COALESCE(SUM(byte_size), 0) <= ? FROM materials WHERE owner_id=?",
                        Boolean.class,
                        ownerMaxFiles,
                        ownerMaxBytes - bytes,
                        ownerId);
        if (!Boolean.TRUE.equals(ownerHasCapacity))
            throw new ApiFailure(409, "material_quota_exceeded");

        try {
            var store = Files.getFileStore(originals);
            if (instanceMaxBytes < bytes
                    || store.getUsableSpace() - minFreeBytes < bytes + store.getBlockSize())
                throw new ApiFailure(503, "material_storage_unavailable");
            long remainingBytes = instanceMaxBytes - bytes;
            long remainingFiles = instanceMaxFiles - 1;
            // shortcut: scan up to the file cap to count orphans; index usage for larger stores.
            try (var originalsOnDisk = Files.newDirectoryStream(originals)) {
                for (Path original : originalsOnDisk) {
                    var attributes =
                            Files.readAttributes(
                                    original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile()
                            || remainingFiles-- == 0
                            || attributes.size() > remainingBytes)
                        throw new ApiFailure(503, "material_storage_unavailable");
                    remainingBytes -= attributes.size();
                }
            }
        } catch (IOException | DirectoryIteratorException failure) {
            throw new ApiFailure(503, "material_storage_unavailable");
        }
    }

    void writeOriginal(String id, byte[] bytes) throws IOException {
        Path pending = Files.createTempFile(originals, ".upload-", ".part");
        try {
            Files.write(pending, bytes);
            try (var channel =
                    java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Path original = originals.resolve(id);
            Files.move(pending, original, StandardCopyOption.ATOMIC_MOVE);
            try (var channel =
                    java.nio.channels.FileChannel.open(originals, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException failure) {
                try {
                    Files.deleteIfExists(original);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } finally {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException failure) {
                org.slf4j.LoggerFactory.getLogger(MaterialService.class)
                        .warn("Material staging cleanup requires retry");
            }
        }
    }

    public byte[] content(Caller caller, String id) {
        var material = get(caller, id);
        try (var stream =
                Files.newInputStream(originals.resolve(material.id()), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            var expected =
                    db.queryForObject(
                            "SELECT sha256 FROM materials WHERE id=? AND owner_id=?",
                            String.class,
                            id,
                            caller.id());
            if (bytes.length != material.byteSize() || !checksum(bytes).equals(expected))
                throw new ApiFailure(503, "material_storage_unavailable");
            return bytes;
        } catch (IOException failure) {
            throw new ApiFailure(503, "material_storage_unavailable");
        }
    }
}
