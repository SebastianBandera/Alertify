import { Status } from "./status";

export class NotificationDto {
    private _name: string;
    private _text: string;
    private _status: Status;

    constructor(name: string, text: string, status: Status) {
        this._name = name;
        this._text = text;
        this._status = status;
    }

    public get name(): string {
        return this._name;
    }
    public set name(value: string) {
        this._name = value;
    }
    public get text(): string {
        return this._text;
    }
    public set text(value: string) {
        this._text = value;
    }
    public get status(): Status {
        return this._status;
    }
    public set status(value: Status) {
        this._status = value;
    }
    
}
