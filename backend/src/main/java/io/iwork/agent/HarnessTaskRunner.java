package io.iwork.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.iwork.conversation.TaskRunner;
import io.iwork.model.*;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HarnessTaskRunner implements TaskRunner {
    private final GovernedModel models;
    private final ModelCallLedger ledger;
    private final Path workspace;
    public HarnessTaskRunner(GovernedModel models,ModelCallLedger ledger,@Value("${iwork.files-directory}") Path root) {
        this.models=models; this.ledger=ledger; this.workspace=root.resolve("harness").toAbsolutePath();
    }
    public String execute(Input input) {
        try(var agent=HarnessAgent.builder().name("iWork").agentId(input.taskId()).workspace(workspace)
            .sysPrompt("你是工作助手。使用中文完成用户的文字任务，不声称执行没有提供的工具。")
            .model(models.forTask(input,"MAIN")).toolkit(new Toolkit()).maxIters(4).maxRetries(1)
            .modelExecutionConfig(GovernedModel.EXECUTION).stateStore(new InMemoryAgentStateStore())
            .disableFilesystemTools().disableShellTool().disableWebTools().disableToolsConfig()
            .disableDynamicSkills().disableDefaultWorkspaceSkills().skillsEnabled(false)
            .disableSubagents().disableDynamicSubagents().enablePlanMode(false).enableMetaTool(false).enableTaskList(false)
            .disableMemoryTools().disableMemoryHooks().disableTranscript().disableSessionPersistence()
            .disableWorkspaceContext().disableAtPathExpansion().enableAgentTracingLog(false)
            .disableCompaction().disableToolResultEviction().enablePendingToolRecovery(false).build()) {
            // Harness 2.0.4 registers this bus tool even when subagents are disabled.
            agent.getToolkit().removeTool("wait_async_results");
            if(!agent.getSkillRepositories().isEmpty()) throw new IllegalStateException("Unexpected Harness skills");
            if(!agent.getToolkit().getToolNames().isEmpty()) throw new IllegalStateException("Unexpected Harness tool");
            var context=RuntimeContext.builder().userId(input.caller().id()).sessionId(input.conversationId()).build();
            var reply=agent.call(Msg.builder().name("user").role(MsgRole.USER).textContent(input.message()).build(),context).block(Duration.ofMinutes(9));
            if(reply==null) throw new IllegalStateException("Missing model result");
            return reply.getTextContent();
        } finally { ledger.retain(() -> ledger.finishTask(input.taskId())); }
    }
}
