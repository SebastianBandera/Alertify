export class IndexedData<K, V> {
    index: Map<K, V>;
    data: V[];

    constructor() {
        this.index = new Map<K, V>();
        this.data = [];
    }
}