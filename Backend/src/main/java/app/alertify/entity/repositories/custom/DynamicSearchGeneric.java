package app.alertify.entity.repositories.custom;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.management.AttributeNotFoundException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.repositories.custom.DynamicSearchCriteria.Criteria;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

@Repository
public class DynamicSearchGeneric<T> implements DynamicSearch<T> {

    @PersistenceContext
    private EntityManager entityManager;
    
	@Value("${timezoneSecondsOffset:0}")
    private long timezoneSecondsOffset;
    
    private final List<CriteriaCondition> OP_LIST;
    
    private final Map<Class<T>, Map<String, Class<?>>> dbFieldsTypesCache;
    
    public DynamicSearchGeneric() {
    	CriteriaCondition equal = new CriteriaCondition("=", Criteria.EQUAL);
    	CriteriaCondition distinct = new CriteriaCondition("!=", Criteria.DISTINCT);
    	CriteriaCondition greater_equal = new CriteriaCondition(">=", Criteria.GREATER_EQUAL);
    	CriteriaCondition less_equal = new CriteriaCondition("<=", Criteria.LESS_EQUAL);
    	CriteriaCondition greater = new CriteriaCondition(">", Criteria.GREATER);
    	CriteriaCondition less = new CriteriaCondition("<", Criteria.LESS);
    	
    	//Order is important, first ones are evaluated first
		this.OP_LIST = Arrays.asList(
			equal,
			distinct,
			greater_equal,
			less_equal,
			greater,
			less
		);
		
		this.dbFieldsTypesCache = new HashMap<Class<T>, Map<String,Class<?>>>();
    }
    
    private <Key, Item> Item simpleCacheable(Map<Key, Item> cache, Key key, Supplier<Item> supplier) {
    	Item item;
        if(cache.containsKey(key)) {
        	item = cache.get(key);
        } else {
        	item = supplier.get();
        	cache.put(key, item);
        }
        
        return item;
    }
    
	@Override
	public DynamicSearchResult<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(type);
        Root<T> root = query.from(type);
        
        List<Predicate> predicates = new ArrayList<>();
        
        //It does not matter if due to concurrency problems it is generated twice unnecessarily, the result to be saved will be the same
        Map<String, Class<?>> dbFieldsTypes = simpleCacheable(dbFieldsTypesCache, type, () -> getColumnFields(type));
        
        List<Exception> errors = new LinkedList<Exception>();
        params.forEach((key, values) -> {
        	if(values == null) return;
        	
        	Class<?> associatedFieldType = dbFieldsTypes.get(key);
        	if (associatedFieldType == null) {
				errors.add(new AttributeNotFoundException(key));
			} else {
				try {
					if (associatedFieldType.equals(String.class)) {
						processString(values, cb, root, key, predicates);
					} else if (associatedFieldType.equals(Integer.class) || associatedFieldType.equals(int.class)) {
						processGeneric(values, errors, value -> Integer.parseInt(value), cb, root, predicates, key);
					} else if (associatedFieldType.equals(Float.class) || associatedFieldType.equals(float.class)) {
						processGeneric(values, errors, value -> Float.parseFloat(value), cb, root, predicates, key);
					} else if (associatedFieldType.equals(Double.class) || associatedFieldType.equals(double.class)) {
						processGeneric(values, errors, value -> Double.parseDouble(value), cb, root, predicates, key);
					} else if (associatedFieldType.equals(Long.class) || associatedFieldType.equals(Long.class)) {
						processGeneric(values, errors, value -> Long.parseLong(value), cb, root, predicates, key);
					} else if (associatedFieldType.equals(Boolean.class) || associatedFieldType.equals(boolean.class)) {
						if (values.size() == 1) {
		                    predicates.add(cb.equal(root.get(key), Boolean.parseBoolean(values.get(0))));
		                } else {
		                    errors.add(new Exception("no multivalue a boolean"));
		                }
					} else if (associatedFieldType.equals(Date.class)) {
						processDate(values, errors, cb, root, predicates, key);
					} else if (associatedFieldType.equals(java.sql.Date.class)) {
						processSqlDate(values, errors, cb, root, predicates, key);
					} else {
					    throw new Exception("Type not recognized.");
					}
	               
				} catch (Exception e) {
					errors.add(e);
				}
			}
        });
        
        query.where(cb.and(predicates.toArray(new Predicate[0])));
        
        if (pageable.getSort().isSorted()) {
            pageable.getSort().forEach(order -> {
                if (order.isAscending()) {
                    query.orderBy(cb.asc(root.get(order.getProperty())));
                } else {
                    query.orderBy(cb.desc(root.get(order.getProperty())));
                }
            });
        }
        
        List<T> results = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        countQuery.select(cb.count(countQuery.from(type)));
        countQuery.where(cb.and(predicates.toArray(new Predicate[0])));
        Long total = entityManager.createQuery(countQuery).getSingleResult();

        return new DynamicSearchResult<T>(new PageImpl<>(results, pageable, total), errors);
	}
    
	private <K extends Comparable<K>> void processGeneric(List<String> values, List<Exception> errors, Function<String, K> convert, CriteriaBuilder cb, Root<T> root, List<Predicate> predicates, String key) {
		List<DynamicSearchCriteria<K>> valuesParsed = parseGeneric(values, errors, convert);
		
		if(!valuesParsed.isEmpty()) {
		    if (values.size() == 1) {
		    	for (DynamicSearchCriteria<K> search : valuesParsed) {
		    		Predicate predicate;
			    	switch (search.getCriteria()) {
					case EQUAL:
						predicate = cb.equal(root.get(key), search.getValue());
						break;
					case DISTINCT:
						predicate = cb.not(cb.equal(root.get(key), search.getValue()));
						break;
					case GREATER:
						predicate = cb.greaterThan(root.get(key), search.getValue());
						break;
					case GREATER_EQUAL:
						predicate = cb.greaterThanOrEqualTo(root.get(key), search.getValue());
						break;
					case LESS:
						predicate = cb.lessThan(root.get(key), search.getValue());
						break;
					case LESS_EQUAL:
						predicate = cb.lessThanOrEqualTo(root.get(key), search.getValue());
						break;
					default:
						predicate = cb.equal(root.get(key), search.getValue());
					}
			    	
			    	predicates.add(predicate);
				}
            } else {
                predicates.add(root.get(key).in(valuesParsed.stream().map(item -> item.getValue()).collect(Collectors.toList())));
            }
		}
	}
	
	private void processDate(List<String> values, List<Exception> errors, CriteriaBuilder cb, Root<T> root, List<Predicate> predicates, String key) {
		processGeneric(values, errors, value -> {
				List<SimpleDateFormat> formatters = getFormatters();
			        
				Date date = null;
            	for (SimpleDateFormat formatter : formatters) {
                    try {
						Date searchDate = formatter.parse(value);
						Date utcDate = new Date(searchDate.getTime() + timezoneSecondsOffset);
						date = utcDate;
                        break;
                    } catch (ParseException ignored) {}
                }
            	
            	if (date==null) {
                    errors.add(new ParseException("Unparseable date: " + value, 0));
                }
            	
            	return date;
			}, cb, root, predicates, key);
	}
	
	private void processSqlDate(List<String> values, List<Exception> errors, CriteriaBuilder cb, Root<T> root, List<Predicate> predicates, String key) {
		processGeneric(values, errors, value -> {
				List<SimpleDateFormat> formatters = getFormatters();
			        
				java.sql.Date date = null;
            	for (SimpleDateFormat formatter : formatters) {
                    try {
                    	java.sql.Date searchDate = new java.sql.Date(formatter.parse(value).getTime());
                    	java.sql.Date utcDate = new java.sql.Date(searchDate.getTime() + timezoneSecondsOffset);
						date = utcDate;
                        break;
                    } catch (ParseException ignored) {}
                }
            	
            	if (date==null) {
                    errors.add(new ParseException("Unparseable date: " + value, 0));
                }
            	
            	return date;
			}, cb, root, predicates, key);
	}
	
	private List<SimpleDateFormat> getFormatters() {
		return Arrays.asList(
	            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
	            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
	            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm"),
	            new SimpleDateFormat("yyyy-MM-dd'T'HH"),
	            new SimpleDateFormat("yyyy-MM-dd")
	        );
	}
	
	private void processString(List<String> values, CriteriaBuilder cb, Root<T> root, String key, List<Predicate> predicates) {
		if(!values.isEmpty()) {
			if (values.size() == 1) {
				String value = values.get(0);
				if(value.contains("%") || value.contains("_")) {
					predicates.add(cb.like(root.get(key), value, '\\'));
				} else {
					predicates.add(cb.equal(root.get(key), value));
				}
            } else {
                predicates.add(root.get(key).in(values));
            }
		}
	}

	private <K> List<DynamicSearchCriteria<K>> parseGeneric(List<String> values, List<Exception> errors, Function<String, K> convert) {
		List<DynamicSearchCriteria<K>> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				K item = null;
				Criteria criteria = Criteria.NULL;
				
				if(value != null) {
					value = value.trim();
					
					if (value.contains("_")) {
						int index = value.indexOf("_");
						String str1 = value.substring(0, index);
						String str2 = value.substring(index + 1);
						
						List<DynamicSearchCriteria<K>> result = parseGeneric(Arrays.asList(str1), errors, convert);
						result.addAll(parseGeneric(Arrays.asList(str2), errors, convert));
						
						return result;
					} else {
						boolean match = false;
						for (CriteriaCondition op : OP_LIST) {
							if(!match && value.startsWith(op.getOp())) {
								item = convert.apply(value.substring(op.getOp().length()));
								criteria = op.getCriteria();
								match = true;
							}
							
							if(match) break;
						}
						
						if(!match) {
							item = convert.apply(value);
							criteria = Criteria.EQUAL;
						}
					}
				}
				
				list.add(new DynamicSearchCriteria<K>(item, criteria));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}

	private Map<String, Class<?>> getColumnFields(Class<T> type) {
		Field[] fields = type.getDeclaredFields();
		Map<String, Class<?>> dbFields = new HashMap<>();
		
		for (int i = 0; i < fields.length; i++) {
			Field field = fields[i];
			
			Annotation annotations[] = field.getAnnotationsByType(javax.persistence.Column.class);
			if(annotations != null && annotations.length > 0) {
				dbFields.put(field.getName(), field.getType());
			}

			Annotation annotationsId[] = field.getAnnotationsByType(javax.persistence.Id.class);
			if(annotationsId != null && annotationsId.length > 0) {
				dbFields.put(field.getName(), field.getType());
			}
			
		}
		
		return dbFields;
	}
}
