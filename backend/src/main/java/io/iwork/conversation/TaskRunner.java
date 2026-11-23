package io.iwork.conversation;

import io.iwork.identity.Caller;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs outside database transactions; only TaskService may publish business results. */
public interface TaskRunner {
    record ToolProgress(String id, String name, String materialLabel, String status) {}

    record Input(
            String taskId,
            String conversationId,
            Caller caller,
            String modelId,
            String message,
            String outputMode,
            String instructions,
            String partialReply,
            BiConsumer<String, String> replyDelta,
            Consumer<ToolProgress> toolProgress) {
        public <T> T observeTool(String name, String materialLabel, Supplier<T> execute) {
            String id = UUID.randomUUID().toString();
            toolProgress.accept(new ToolProgress(id, name, materialLabel, "RUNNING"));
            T result;
            try {
                result = execute.get();
            } catch (RuntimeException failure) {
                toolProgress.accept(new ToolProgress(id, name, materialLabel, "FAILED"));
                throw failure;
            }
            toolProgress.accept(new ToolProgress(id, name, materialLabel, "COMPLETED"));
            return result;
        }

        public String prompt() {
            return partialReply.isEmpty()
                    ? message
                    : message + "\n\n请从已生成回复的末尾接着写，只输出新增内容。以下是已生成的回复：\n" + partialReply;
        }
    }

    String execute(Input input) throws Exception;
}
