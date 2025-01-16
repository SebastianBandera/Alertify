package app.alertify.controller.dto;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SimpleMapper {

	private static final Logger log = LoggerFactory.getLogger(SimpleMapper.class);
	
	public SimpleMapper() {
		
	}

	public <T, K> T map(K input, Class<T> classOutput) {
		return map(input, classOutput, new HashMap<>());
	}

	public <T, K> T map(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations) {
		try {
			return tryMap(input, classOutput, typeRelations);
		} catch (InstantiationException | IllegalAccessException | SecurityException | InvocationTargetException e) {
			log.error("error with tryMap", e);
			return null;
		}
	}
	
	public <T, K> T tryMap(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations) throws InstantiationException, IllegalAccessException, SecurityException, InvocationTargetException {
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
								Object newValue = tryMap(valueOut, newType, typeRelations);
								valueOut = newValue;
							}
						}
					}
					Method mtdOut = classOutput.getDeclaredMethod("set" + mtdNameSuffix, types);
					mtdOut.invoke(output, valueOut);
				} catch (NoSuchMethodException | IllegalArgumentException e) {}
			}
		}
		
		return output;
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
