package com.deductionaudit.config;

import com.deductionaudit.tools.EvidenceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class McpToolsConfiguration {

    @Bean
    ToolCallbackProvider evidenceToolCallbacks(EvidenceTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
