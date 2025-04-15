export class GeneralUtils {

    public async wait(ms: number): Promise<void> {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
    
    public parseIsoDuration(isoDuration: string): string {
        const match = isoDuration.match(/P(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?/);
      
        if (!match) return "invalid format";
      
        const [, hours, minutes, seconds] = match.map(val => val ? parseInt(val) : 0);
        const parts = [];
        
        if (hours) if(hours==1) {parts.push(`${hours} hour`)} else {parts.push(`${hours} hours`)}
        if (minutes) if(minutes==1) {parts.push(`${minutes} minute`)} else {parts.push(`${minutes} minutes`)}
        if (seconds) if(seconds==1) {parts.push(`${seconds} seconds`)} else {parts.push(`${seconds} secondss`)}
      
        return parts.length ? parts.join(", ") : "0 seconds";
    }

    public tryableRunnable(runnable: ()=>void, callback: (error: unknown)=>void): void {
        try {
            runnable();
        } catch (error: unknown) {
            callback(error);
        }
    }

    public tryableSupplier<T>(supplier: ()=>T, callback: (error: unknown)=>void, defaultValue: () => T): T {
        try {
            return supplier();
        } catch (error: unknown) {
            callback(error);
            return defaultValue();
        }
    }

    public formatDate(dateString: string): string {
        const date = new Date(dateString);
        return date.toLocaleString("es-ES", {
            day: "numeric",
            month: "numeric",
            year: "numeric",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
        });
    }

    public diffInMinutes(date1: Date, date2: Date): number {
        const diffMs = Math.abs(date2.getTime() - date1.getTime());
        return Math.floor(diffMs / 60000);
    }    
}
