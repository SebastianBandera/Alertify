import { Injectable } from "@angular/core";
import { Alert, AlertResult, StatusResult } from "../data/service.dto";

@Injectable({
  providedIn: 'root'
})
export class MessageService {

    public process(alertResult: AlertResult): { mensaje: string; descripcion: string } {
        try {
            const control = alertResult.alert.control;
            const resultJSON: any = JSON.parse(alertResult.result);
            const descripcion: string = resultJSON.descripcion;

            switch(control) {
                case "39ea1606ac82c63f59dddccbcb88aa0a": return {mensaje: this.process39ea1606ac82c63f59dddccbcb88aa0a(alertResult, resultJSON), descripcion: descripcion};
                case "74e53c338015a4a9f226cabd620cd23c": return {mensaje: this.process74e53c338015a4a9f226cabd620cd23c(alertResult, resultJSON), descripcion: descripcion};
                case "ad6aaa83e8eb0a24cffd0513e95e2c1b": return {mensaje: this.processad6aaa83e8eb0a24cffd0513e95e2c1b(alertResult, resultJSON), descripcion: descripcion};
                default: return {mensaje: "", descripcion: descripcion}
            }
        } catch (error) {
            return {mensaje: "", descripcion: ""};
        }
    }
    
    private process39ea1606ac82c63f59dddccbcb88aa0a(alertResult: AlertResult, resultJSON: any): string {
        const status: StatusResult = alertResult.statusResult;

        if(status.name == 'success') {
            return "Éxito";
        } else {
            const thresholdType: string = resultJSON.thresholdType;
            const threshold: number = resultJSON.threshold;
            const count: number = resultJSON.count;
    
            switch(thresholdType) {
                case "warn_if_bigger":
                    return count + ", pero el máximo definido era " + threshold;
                case "warn_if_lower":
                    return count + ", pero el mínimo definido era " + threshold;
                case "warn_if_equal":
                    return count + ", pero la cantidad definida era igual a " + threshold;
                case "warn_if_distinct":
                    return count + ", pero la cantidad definido era distinta a " + threshold;
                default: 
                    return "thresholdType no reconocido"
            }
        }
    }

    private process74e53c338015a4a9f226cabd620cd23c(alertResult: AlertResult, resultJSON: any): string {
        const status: StatusResult = alertResult.statusResult;

        if(status.name == 'success') {
            return "Éxito";
        } else {
            return "Fallo en la conexión";
        }
    }

    private processad6aaa83e8eb0a24cffd0513e95e2c1b(alertResult: AlertResult, resultJSON: any): string {
        const status: StatusResult = alertResult.statusResult;

        if(status.name == 'success') {
            return "Éxito";
        } else {
            const statusCode: number = resultJSON.statusCode;
            const response_code_expected: number = resultJSON.response_code_expected;
            const regex_result_isvalid: boolean | undefined = resultJSON.regex_result_isvalid;
            const regex_result_no_valid_index: number | undefined = resultJSON.regex_result_no_valid_index;
            
            if(regex_result_isvalid == undefined) {
                if(statusCode == response_code_expected) {
                    return "";
                } else {
                    return "Se esperaba un " + response_code_expected + " pero se recibió un " + statusCode;
                }
            } else {
                let msg: string;
                if(statusCode == response_code_expected) {
                    msg = "";
                } else {
                    msg = "Se esperaba un " + response_code_expected + " pero se recibió un " + statusCode;
                }
                if(regex_result_isvalid == false) {
                    msg = msg + ". Se encontró un problema según la regex."; 
                }

                return msg;
            }
        }
    }
}