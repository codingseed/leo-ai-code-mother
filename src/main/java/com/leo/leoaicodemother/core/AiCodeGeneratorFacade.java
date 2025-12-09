package com.leo.leoaicodemother.core;

import cn.hutool.json.JSONUtil;
import com.leo.leoaicodemother.ai.AiCodeGeneratorService;
import com.leo.leoaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.leo.leoaicodemother.ai.model.HtmlCodeResult;
import com.leo.leoaicodemother.ai.model.MultiFileCodeResult;
import com.leo.leoaicodemother.ai.model.message.AiResponseMessage;
import com.leo.leoaicodemother.ai.model.message.ToolExecutedMessage;
import com.leo.leoaicodemother.ai.model.message.ToolRequestMessage;
import com.leo.leoaicodemother.constant.AppConstant;
import com.leo.leoaicodemother.core.builder.VueProjectBuilder;
import com.leo.leoaicodemother.core.parser.CodeParserExecutor;
import com.leo.leoaicodemother.core.saver.CodeFileSaverExecutor;
import com.leo.leoaicodemother.exception.BusinessException;
import com.leo.leoaicodemother.exception.ErrorCode;
import com.leo.leoaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成门面类，组合代码生成和保存功能
 * 该类作为系统对外提供代码生成服务的统一入口，封装了不同类型代码的生成和保存逻辑
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory; // AI代码生成服务工厂，用于获取不同类型的代码生成服务

    @Resource
    private VueProjectBuilder vueProjectBuilder; // Vue项目构建器，用于构建Vue项目

    /**
     * 统一入口：根据类型生成并保存代码
     * 同步方法，适用于一次性生成完整代码的场景

     *
     * @param userMessage     用户提示词，描述需要生成的代码需求
     * @param codeGenTypeEnum 生成类型，枚举类型，指定要生成的代码类型
     * @param appId           应用 ID，用于标识不同的应用
     * @return 保存的目录，返回代码保存后的文件路径
     * @throws BusinessException 当生成类型为空或系统不支持该类型时抛出
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        // 根据 appId 获取相应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     * 异步方法，适用于流式生成代码的场景，可以实时获取生成进度

     *
     * @param userMessage     用户提示词，描述需要生成的代码需求
     * @param codeGenTypeEnum 生成类型，枚举类型，指定要生成的代码类型
     * @param appId           应用 ID，用于标识不同的应用
     * @return 保存的目录，返回代码保存后的文件路径
     * @throws BusinessException 当生成类型为空或系统不支持该类型时抛出
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        // 根据 appId 获取相应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     * 处理Vue项目生成时的流式响应，包括代码片段、工具调用和执行结果等

     *
     * @param tokenStream TokenStream 对象，包含流式生成的数据
     * @param appId       应用 ID，用于标识不同的应用
     * @return Flux<String> 流式响应，包含生成的代码和工具调用信息
     */
    private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
        return Flux.create(sink -> {
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        // 执行 Vue 项目构建（同步执行，确保预览时项目已就绪）
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                        vueProjectBuilder.buildProject(projectPath);
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }

    /**
     * 通用流式代码处理方法
     * 处理HTML和多文件类型的流式代码生成，收集完整代码后进行保存

     *
     * @param codeStream  代码流，包含流式生成的代码片段
     * @param codeGenType 代码生成类型，指定要生成的代码类型
     * @param appId       应用 ID，用于标识不同的应用
     * @return 流式响应，包含生成的代码和保存结果
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        // 字符串拼接器，用于当流式返回所有的代码之后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            // 流式返回完成后，保存代码
            try {
                String completeCode = codeBuilder.toString();
                // 使用执行器解析代码
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                // 使用执行器保存代码
                File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
            }
        });
    }
}
