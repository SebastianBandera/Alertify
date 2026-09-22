package app.alertify.worker.runtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerRuntimeProperties.class)
@Import({
    WorkerInstanceIdentity.class,
    AlertTemplateCompiler.class,
    WorkerExecutionTracker.class,
    WorkerResourceMonitor.class,
    BinaryExecutionGuard.class,
    WorkerArtifactStore.class,
    WorkerExecutionEngine.class,
    ProcedureExecutionEngine.class,
    AlertWorkerGrpcService.class,
    WorkerGrpcServer.class
})
public class WorkerRuntimeConfiguration {
}
