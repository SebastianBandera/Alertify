export class CollectionUtils {

    public upsertItemArrayByIdExtractor<T extends object>(array: T[], newItem: T, keyExtractor: (item:T) => any): boolean {
        return this.upsertItemKeysArrayByIdExtractor(array, newItem, null, keyExtractor);
    }

    public upsertItemKeysArrayByIdExtractor<T extends object, K extends keyof T>(array: T[], newItem: T, keysToAssign: K[] | null, keyExtractor: (item:T) => any): boolean {
        const index: number = array.findIndex(item => keyExtractor(item) === keyExtractor(newItem));

        if (index !== -1) {
            this.upsertItemKeys(array[index], newItem, keysToAssign)
            return false;
        } else {
            array.push(newItem);
            return true;
        }
    }

    public upsertItemArray<T extends { id: any }>(array: T[], newItem: T): boolean {
        return this.upsertItemKeysArray(array, newItem, null);
    }

    public upsertItemKeysArray<T extends { id: any }, K extends keyof T>(array: T[], newItem: T, keysToAssign: K[] | null): boolean {
        const index = array.findIndex(item => item.id === newItem.id);

        if (index !== -1) {
            this.upsertItemKeys(array[index], newItem, keysToAssign)
            return false;
        } else {
            array.push(newItem);
            return true;
        }
    }

    public upsertItem<T extends object>(original: T, newItem: T): void {
        this.upsertItemKeys(original, newItem, null);
    }

    public upsertItemKeys<T extends object, K extends keyof T>(original: T, newItem: T, keysToAssign: K[] | null): void {
        if(keysToAssign == null) {
            Object.assign(original, newItem);
        } else {
            this.assignSelectedProperties(original, newItem, keysToAssign);
        }
    }

    public assignSelectedProperties<T extends object, K extends keyof T>(target: Partial<T>, source: T, keysToAssign: K[]): void {
        keysToAssign.forEach(key => {
                if (key in source) {
                    target[key] = source[key];
                }
            });
    }

}
