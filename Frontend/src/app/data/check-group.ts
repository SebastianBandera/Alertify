import { Check } from "./check";

export class CheckGroup {
    private _name: string;
    private _checks: Check[];

    constructor(name: string, checks:Check[]) {
        this._name = name;
        this._checks = checks;
    }

    public get name(): string {
        return this._name;
    }
    public set name(value: string) {
        this._name = value;
    }
    
    public get checks(): Check[] {
        return this._checks;
    }
    public set checks(value: Check[]) {
        this._checks = value;
    }
}
