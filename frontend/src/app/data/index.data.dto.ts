export class IndexedData<K, V> {
    private index: Map<K, V>;
    private data: V[];

    constructor() {
        this.index = new Map<K, V>();
        this.data = [];
    }

    get getAllData(): V[] {
        return this.data;
    }

    get getIndex(): Map<K, V> {
        return this.index;
    }

    public setIndexed(item: V, keyGetter: (arg: V) => K): void {
        if (item != null && keyGetter != null) {
          const id: K = keyGetter(item);
          if (!this.index.has(id)) {
            this.data.push(item);
          }
    
          this.index.set(id, item);
        }
    }
}