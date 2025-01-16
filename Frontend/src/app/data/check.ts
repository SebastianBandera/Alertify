import { ErrorCheck } from "./error-check";
import { Status } from "./status";

export class Check {
    private _name: string;
    private _status: Status;
    private _lastSuccess: Date;
    private _period: Number;
    private _errors: ErrorCheck[];

    constructor(name: string, status: Status, lastSuccess: Date, period: Number, errors: ErrorCheck[]) {
        this._name = name;
        this._status = status;
        this._lastSuccess = lastSuccess;
        this._period = period;
        this._errors = errors;
    }

    public get name(): string {
        return this._name;
    }
    public set name(value: string) {
        this._name = value;
    }
    public get status(): Status {
        return this._status;
    }
    public set status(value: Status) {
        this._status = value;
    }
    public get lastSuccess(): Date {
        return this._lastSuccess;
    }
    public set lastSuccess(value: Date) {
        this._lastSuccess = value;
    }
    public get period(): Number {
        return this._period;
    }
    public set period(value: Number) {
        this._period = value;
    }
    public get errors(): ErrorCheck[] {
        return this._errors;
    }
    public set errors(value: ErrorCheck[]) {
        this._errors = value;
    }
    
}
