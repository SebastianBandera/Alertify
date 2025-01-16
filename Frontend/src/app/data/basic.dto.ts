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