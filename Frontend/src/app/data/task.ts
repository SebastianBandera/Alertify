import { Alert, AlertResult, Group } from "./basic.dto";

export interface Task {
    type: TaskType;
    msg: string;
    data: Group[] | Alert[] | AlertResult[];
}

export enum TaskType {
    NA,
    GROUPS,
    NO_GROUPS,
    ALERTS,
    ALERT_RESULTS,
}
