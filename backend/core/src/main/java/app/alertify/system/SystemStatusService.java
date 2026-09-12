package app.alertify.system;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.alerts.execution.CronQuietHoursService;
import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.api.WorkerTaskStatusResponse;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.system.api.SystemStatusSummaryResponse;
import app.alertify.system.api.WorkerQueueStatusResponse;

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
    private final CronQuietHoursService cronQuietHoursService;
    private final WorkerStatusService workerStatusService;

    public SystemStatusService(MaintenanceModeService maintenanceModeService, CronQuietHoursService cronQuietHoursService, WorkerStatusService workerStatusService) {
        this.maintenanceModeService = maintenanceModeService;
        this.cronQuietHoursService = cronQuietHoursService;
        this.workerStatusService = workerStatusService;
    }

    public SystemStatusSummaryResponse summary() {
        // The interactive status view records the WORKER_STATUS_VIEWED audit event.
        return summary(workerStatusService.status());
    }

    public SystemStatusSummaryResponse tickerSummary() {
        // The frequently refreshed ticker deliberately avoids that audit side effect.
        return summary(workerStatusService.tickerStatus());
    }

    private SystemStatusSummaryResponse summary(List<WorkerNodeStatusResponse> workers) {
        return new SystemStatusSummaryResponse(
                maintenanceModeService.isActive(),
                cronQuietHoursService.isQuietNow(),
                count(workers, WorkerNodeStatusResponse::runningTasks, ALERT_KIND),
                count(workers, WorkerNodeStatusResponse::waitingTasks, ALERT_KIND),
                count(workers, WorkerNodeStatusResponse::runningTasks, PROCEDURE_KIND),
                count(workers, WorkerNodeStatusResponse::waitingTasks, PROCEDURE_KIND),
                workers.stream()
                        .filter(WorkerNodeStatusResponse::available)
                        .filter(worker -> worker.waitingCount() > 1)
                        .map(worker -> new WorkerQueueStatusResponse(workerName(worker), worker.waitingCount()))
                        .toList()
        );
    }

    private static String workerName(WorkerNodeStatusResponse worker) {
        return worker.workerName() == null || worker.workerName().isBlank() ? worker.address() : worker.workerName();
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
