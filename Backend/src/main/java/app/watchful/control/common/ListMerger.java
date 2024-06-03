package app.watchful.control.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ListMerger<T> {

    private final List<Function<T, Object>> keyExtractors;
    private final List<Function<T, Object>> valueExtractors;

    private BiFunction<T, T, T> functionCaseChanged = null;

    private MergeResults mergeResults;
    private Map<ArrayObjects, List<T>> mainGroupingCopy;

    public ListMerger(List<Function<T, Object>> keyExtractors, List<Function<T, Object>> valueExtractors) {
        this.keyExtractors = keyExtractors;
        this.valueExtractors = valueExtractors;
    }

    public MergeResults merge(List<T> mainList, List<T> incomingList) throws Exception {
        Map<ArrayObjects, List<T>> mainGrouping = groupingByExtractor(mainList, this.keyExtractors);
        Map<ArrayObjects, List<T>> incomingGrouping = groupingByExtractor(incomingList, this.keyExtractors);
        checkUniqueGrouping(mainGrouping, "La lista principal tiene repetidos");
        checkUniqueGrouping(incomingGrouping, "La lista secundaria tiene repetidos");

        mainGroupingCopy = new HashMap<>(mainGrouping);
        mergeResults = new MergeResults();

        for (T item : incomingList) {
            processIncomingItem(item, mainGrouping);
        }

        for (Map.Entry<ArrayObjects, List<T>> item : mainGroupingCopy.entrySet()) {
            mergeResults.getRemovedItems().add(item.getValue().get(0));
        }

        return mergeResults;
    }

    private void processIncomingItem(T item, Map<ArrayObjects, List<T>> grouping) {
        ArrayObjects keyObjectIncomingItem = createArrayObject(item, keyExtractors);

        T mainItem = search(keyObjectIncomingItem, grouping);

        if (mainItem == null) {
            mergeResults.getNewItems().add(item);
        } else {
            //Remueve de la copia los elementos que encontró el "search". Aquellos que no encuentre, 
            //serán los que no están en "incomingList", osea los eliminados.
            mainGroupingCopy.remove(keyObjectIncomingItem);

            //Identico o update necesario por algun cambio en valueExtractor.
            boolean changed = hasChangedValues(item, mainItem);
            if (changed) {
                T tmp = item;
                if (functionCaseChanged != null) {
                    tmp = functionCaseChanged.apply(mainItem, item);
                }
                mergeResults.getChangedItems().add(tmp);
            } else {
                mergeResults.getIdenticalItems().add(item);
            }
        }
    }

    private boolean hasChangedValues(T item, T incomingItem) {
        ArrayObjects itemArrayObjects = createArrayObject(item, valueExtractors);
        ArrayObjects incomingItemArrayObjects = createArrayObject(incomingItem, valueExtractors);

        return !Objects.equals(itemArrayObjects, incomingItemArrayObjects);
    }

    private T search(ArrayObjects keyObject, Map<ArrayObjects, List<T>> grouping) {
        T incomingItem = getElementFromGrouping(keyObject, grouping);

        return incomingItem;
    }

    @SuppressWarnings("unchecked")
	private T getElementFromGrouping(ArrayObjects key, Map<ArrayObjects, List<T>> grouping) {
        List<T> list = grouping.get(key);
        if (list == null) {
            return null;
        } else {
            if (list.get(0) instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) list.get(0);
                List<Map.Entry<String, Object>> entriesToModify = new ArrayList<>();

                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Object mapValue = entry.getValue();

                    if (mapValue != null) {
                        // Agrega la entrada actual a la lista de entradas a modificar
                        entriesToModify.add(entry);
                    }
                }

                for (Map.Entry<String, Object> entry : entriesToModify) {
                    Object mapKey = entry.getKey();
                    Object mapValue = entry.getValue();

                    // Elimina la entrada existente
                    map.remove(mapKey.toString());

                    // Agrega una nueva entrada con la clave actualizada y el valor formateado
                    map.put(mapKey.toString(), formatearFloat(mapValue));
                }
                return (T) map;
            } else {
                return list.get(0);
            }

        }

    }

    private static Object formatearFloat(Object valor) {
        String strValue = valor.toString();
        if (strValue.contains(".") && isFloat(strValue)) {
            return ((Float) Float.parseFloat(strValue)).toString();
        }

        // Si no es un número o no cumple con las condiciones anteriores, devolver el valor original
        return valor;

    }

    private static boolean isFloat(String str) {
        try {
            Float.parseFloat(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void checkUniqueGrouping(Map<ArrayObjects, List<T>> grouping, String msgError) throws Exception {
        boolean unicos = grouping.values().stream().filter(Objects::nonNull).allMatch(l -> l.size() == 1);

        if (!unicos) {
            throw new Exception(msgError);
        }
    }

    private Map<ArrayObjects, List<T>> groupingByExtractor(List<T> list, List<Function<T, Object>> keyExtractors) {
        Map<ArrayObjects, List<T>> grouping = list.stream().collect(Collectors.groupingBy(
                item -> createArrayObject(item, keyExtractors))
        );

        return grouping;
    }

    public ArrayObjects createArrayObject(T item, List<Function<T, Object>> keyExtractors) {
        int keyExtractorsSize = keyExtractors.size();
        Object[] keyValues = new Object[keyExtractorsSize];
        for (int i = 0; i < keyExtractorsSize; i++) {
            Object keyValue = keyExtractors.get(i).apply(item);
            keyValues[i] = keyValue;
        }

        return new ArrayObjects(keyValues);
    }

    public List<List<T>> getDuplicatedItems(List<T> list) {
        Map<ArrayObjects, List<T>> grouping = groupingByExtractor(list, this.keyExtractors);

        List<List<T>> resultList = grouping.values()
                .stream()
                .filter(Objects::nonNull)
                .filter(l -> l.size() > 1)
                .collect(Collectors.toList());

        return resultList;
    }

    public BiFunction<T, T, T> getFunctionCaseChanged() {
        return functionCaseChanged;
    }

    public void setFunctionCaseChanged(BiFunction<T, T, T> functionCaseChanged) {
        this.functionCaseChanged = functionCaseChanged;

    }

    public class ArrayObjects {

        private final Object[] objects;

        public ArrayObjects(Object[] objects) {
            this.objects = objects;
        }

        public Object[] getObjects() {
            return objects;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 79 * hash + Arrays.deepHashCode(this.objects);
            return hash;
        }

        @SuppressWarnings("unchecked")
		@Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final ArrayObjects other = (ArrayObjects) obj;

            return Arrays.equals(this.objects, other.objects);
        }

        @Override
        public String toString() {
            return "ArrayObjects{objects=" + Arrays.toString(objects) + '}';
        }
    }

    public class MergeResults {

        private List<T> newItems;
        private List<T> identicalItems;
        private List<T> changedItems;
        private List<T> removedItems;

        public MergeResults() {
            this.newItems = new LinkedList<>();
            this.identicalItems = new LinkedList<>();
            this.changedItems = new LinkedList<>();
            this.removedItems = new LinkedList<>();
        }

        public List<T> getNewItems() {
            return newItems;
        }

        public void setNewItems(List<T> newItems) {
            this.newItems = newItems;
        }

        public List<T> getIdenticalItems() {
            return identicalItems;
        }

        public void setIdenticalItems(List<T> identicalItems) {
            this.identicalItems = identicalItems;
        }

        public List<T> getChangedItems() {
            return changedItems;
        }

        public void setChangedItems(List<T> changedItems) {
            this.changedItems = changedItems;
        }

        public List<T> getRemovedItems() {
            return removedItems;
        }

        public void setRemovedItems(List<T> removedItems) {
            this.removedItems = removedItems;
        }
    }

    @SuppressWarnings("rawtypes")
	public static <T extends Map> List<Function<T, Object>> getExtractorsFromMap(String... keys) {
        if (keys == null || keys.length == 0) {
            throw new IllegalArgumentException("Missing args");
        }

        List<Function<T, Object>> resultList = new LinkedList<>();

        for (String key : keys) {
            resultList.add((map) -> map.get(key));
        }

        return resultList;
    }

    public static BiFunction<Map<String, Object>, Map<String, Object>, Map<String, Object>> getFunctionChangedForStringObjectMap(String addSuffix) {
        return (mapMain, mapIncoming) -> {
            Map<String, Object> combined = new HashMap<>(mapIncoming);
            for (String key : mapMain.keySet()) {
            	if (!Objects.equals(mapMain.get(key), mapIncoming.get(key))) {
            		combined.put(key + addSuffix, mapMain.get(key));
				}
            }

            return combined;
        };
    }
}
