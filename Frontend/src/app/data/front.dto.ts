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
    last_error?: Date;
    open: boolean;
    last_success?: Date;
    checks?: FrontChecks[];
}

export interface FrontChecks {
    status: Status;
    period: Number;
    open: boolean;
    results: FrontResult[];
}

export interface FrontResult {
    alert_result: AlertResult;
    time: Date;
    message: string;
    status: Status;
}