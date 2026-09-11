package app.alertify.system;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.api.WorkerTaskStatusResponse;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.system.api.SystemStatusSummaryResponse;

/**
 * Aggregates maintenance mode with in-flight work across all workers, reusing
 * {@link WorkerStatusService} rather than tracking a separate count: each
 * worker task is already tagged with its {@code kind} ("ALERT" or
 * "PROCEDURE"), so summing the matching tasks across workers gives an
 * accurate live count without any new state.
 */
@Service
public class SystemStatusService {

    private static final String ALERT_KIND = "ALERT";
    private static final String PROCEDURE_KIND = "PROCEDURE";

    private final MaintenanceModeService maintenanceModeService;
    private final WorkerStatusService workerStatusService;

    public SystemStatusService(MaintenanceModeService maintenanceModeService, WorkerStatusService workerStatusService) {
        this.maintenanceModeService = maintenanceModeService;
        this.workerStatusService = workerStatusService;
    }

    public SystemStatusSummaryResponse summary() {
        List<WorkerNodeStatusResponse> workers = workerStatusService.status();
        return new SystemStatusSummaryResponse(
                maintenanceModeService.isActive(),
                count(workers, WorkerNodeStatusResponse::runningTasks, ALERT_KIND),
                count(workers, WorkerNodeStatusResponse::waitingTasks, ALERT_KIND),
                count(workers, WorkerNodeStatusResponse::runningTasks, PROCEDURE_KIND),
                count(workers, WorkerNodeStatusResponse::waitingTasks, PROCEDURE_KIND)
        );
    }

    private static int count(List<WorkerNodeStatusResponse> workers, Function<WorkerNodeStatusResponse, List<WorkerTaskStatusResponse>> tasksOf, String kind) {
        int total = 0;
        for (WorkerNodeStatusResponse worker : workers)
            for (WorkerTaskStatusResponse task : tasksOf.apply(worker))
                if (kind.equals(task.kind()))
                    total++;

        return total;
    }
}
