import { Alert, AlertResult, GroupWithAlerts } from "./service.dto";
import { Status } from "./status.enum";

export interface FrontGroupWithAlerts {
    name: string;
    group_with_alerts: GroupWithAlerts;
    alerts: FrontAlert[];
}

export interface FrontAlert {
    alert: Alert;
    status?: Status;
    open: boolean;
    open_errors: boolean;
    period: string;
    last_success?: Date;
    last_issue?: Date;
    results: FrontResult[];
}

export interface FrontResult {
    alert_result: AlertResult;
    time: string;
    message: string;
    status: Status;
    descripcion?: string;
}
