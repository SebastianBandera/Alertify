package app.alertify.controller.dto;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.alertify.controller.dto.MapperConfig.MapFunction;

@Service
public class SimpleMapper {

	private static final Logger log = LoggerFactory.getLogger(SimpleMapper.class);
	
	public SimpleMapper() {
		
	}

	public <T, K> T map(K input, Class<T> classOutput, Map<Class<?>, List<MapFunction>> mapFunctions) {
		return map(input, classOutput, new HashMap<>(), mapFunctions);
	}

	public <T, K> T map(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations, Map<Class<?>, List<MapFunction>> mapFunctions) {
		try {
			return tryMap(input, classOutput, typeRelations, mapFunctions);
		} catch (InstantiationException | IllegalAccessException | SecurityException | InvocationTargetException e) {
			log.error("error with tryMap", e);
			return null;
		}
	}
	
	public <T, K> T tryMap(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations, Map<Class<?>, List<MapFunction>> mapFunctions) throws InstantiationException, IllegalAccessException, SecurityException, InvocationTargetException {
		Method[] methods = input.getClass().getDeclaredMethods();
		
		if(methods == null) {
			return null;
		}
		
		T output = classOutput.newInstance();
		
		for (int i = 0; i < methods.length; i++) {
			Method mtd = methods[i];
			String mtdName = mtd.getName();
			if (mtdName.startsWith("get") || mtdName.startsWith("is")) {
				int prefixLen = mtdName.startsWith("get") ? 3 : 2;
				String mtdNameSuffix = mtdName.length() > prefixLen ? mtdName.substring(prefixLen) : mtdName;
				
				try {
					Method setter = findSet(methods, mtdNameSuffix);
					if (setter == null) {
						continue;
					}
					Object valueOut = mtd.invoke(input);
					Class<?>[] types = setter.getParameterTypes();
					if(types != null && types.length > 0) {
						for (int j = 0; j < types.length; j++) {
							Class<?> newType = typeRelations.get(types[j]);
							if (newType != null) {
								types[j] = newType;
								Object newValue = tryMap(valueOut, newType, typeRelations, mapFunctions);
								valueOut = newValue;
							}
						}
					}
					Method mtdOut = classOutput.getDeclaredMethod("set" + mtdNameSuffix, types);
					
					MapFunction mapFunction = getMapFunction(mapFunctions, classOutput, mtdNameSuffix);
					if(mapFunction != null) {
						valueOut = mapFunction.getTranslate().apply(valueOut);
					}
					
					mtdOut.invoke(output, valueOut);
				} catch (NoSuchMethodException | IllegalArgumentException e) {}
			}
		}
		
		return output;
	}

	private MapFunction getMapFunction(Map<Class<?>, List<MapFunction>> mapFunctions, Class<?> mainType, String name) {
		if(mapFunctions == null || mainType == null || name == null || !mapFunctions.containsKey(mainType)) return null;
		
		List<MapFunction> mfs =  mapFunctions.get(mainType)
											 .stream()
											 .filter(Objects::nonNull)
											 .filter(obj -> obj.getFieldName().equalsIgnoreCase(name))
											 .collect(Collectors.toList());
		
		if(mfs == null || mfs.isEmpty()) {
			return null;
		}
		
		if(mfs.size() > 1) {
			log.error("Attribute '" + name + "' is not unique for non-case-sensitive search");
			return null;
		}
		
		return mfs.get(0);
	}

	private Method findSet(Method[] methods, String mtdNameSuffix) {
		Method mtdresult = null;
		
		for (Method mtd : methods) {
			if (mtd.getName().equals("set" + mtdNameSuffix)) {
				mtdresult = mtd;
				break;
			}
		}
		
		return mtdresult;
	}
}
