import { Status } from "./status.enum";

export class ErrorCheck {
    private _time: Date;
    private _message: string;
    private _status: Status;

    constructor(time: Date, message: string, status: Status) {
        this._time = time;
        this._message = message;
        this._status = status;
    }
    
    public get time(): Date {
        return this._time;
    }
    public set time(value: Date) {
        this._time = value;
    }
    public get message(): string {
        return this._message;
    }
    public set message(value: string) {
        this._message = value;
    }
    public get status(): Status {
        return this._status;
    }
    public set status(value: Status) {
        this._status = value;
    }

}
