import { Alert, AlertExtradata, AlertResult, Group } from "./service.dto";

export interface Task {
    type: TaskType;
    msg: string;
    data: Group[] | Alert[] | AlertResult[] | AlertExtradata;
}

export enum TaskType {
    NA,
    GROUPS,
    NO_GROUPS,
    ALERTS,
    ALERT_RESULTS,
    ALERT_RESULTSEXTRA_DATA,
}
