export interface Alert {
    id: number;
    name: string;
    periodicity: string;
}

export interface Group {
    id: number;
    name: string;
    alert: Alert;
}

export interface GroupWithAlerts {
    name: string;
    alerts: Alert[];
}

export interface DateResponse {
    date: string;
}

export interface AlertExtradata {
    alertId: number,
    lastSucess: DateResponse;
}

export interface PagedResponse<T> {
    content: T[];
    last: boolean;
    totalPages: number;
    totalElements: number;
    size: number;
    number: number;
    sort: {
        empty: boolean;
        sorted: boolean;
        unsorted: boolean;
    };
    numberOfElements: number;
    first: boolean;
    empty: boolean;
}

export interface StatusResult {
    id: number;
    name: string;
}

export enum StatusResultEnum {
    SUCCESS = "success",
    WARN = "warn",
    ERROR = "error",
  }

export interface AlertResult {
    id: number;
    alert: Alert;
    dateIni: string;
    dateEnd: string;
    statusResult: StatusResult;
    result: string;
    needsReview: boolean;
}

export interface ApiPagedResponse<T> {
    page: PagedResponse<T>;
    errorMessages: string[];
}