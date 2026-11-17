package io.iwork;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

class AgentScopeCompatibilityTest {
    @Test
    void sdkMessageRoundTripsWithBootManagedDependencies() {
        var message = Msg.builder().name("user").role(MsgRole.USER).textContent("兼容性检查").build();
        var codec = JsonUtils.getJsonCodec();
        var restored = codec.fromJson(codec.toJson(message), Msg.class);

        assertThat(restored.getId()).isEqualTo(message.getId());
        assertThat(restored.getRole()).isEqualTo(MsgRole.USER);
        assertThat(restored.getTextContent()).isEqualTo("兼容性检查");
    }
}
