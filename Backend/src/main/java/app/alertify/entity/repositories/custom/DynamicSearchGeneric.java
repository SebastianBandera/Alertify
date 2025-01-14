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

import javax.management.AttributeNotFoundException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

@Repository
public class DynamicSearchGeneric<T> implements DynamicSearch<T> {

    @PersistenceContext
    private EntityManager entityManager;
    
	@Override
	public DynamicSearchResult<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(type);
        Root<T> root = query.from(type);
        
        List<Predicate> predicates = new ArrayList<>();
        
        Map<String, Class<?>> dbFieldsTypes = getColumnFields(type);
        
        List<Exception> errors = new LinkedList<Exception>();
        params.forEach((key, values) -> {
        	if(values == null) return;
        	Class<?> associatedFieldType = dbFieldsTypes.get(key);
        	if (associatedFieldType == null) {
				errors.add(new AttributeNotFoundException(key));
			} else {
				try {
					if (associatedFieldType.equals(String.class)) {
						if(!values.isEmpty()) {
							if (values.size() == 1) {
			                    predicates.add(cb.equal(root.get(key), values.get(0)));
			                } else {
			                    predicates.add(root.get(key).in(values));
			                }
						}
					} else if (associatedFieldType.equals(Integer.class) || associatedFieldType.equals(int.class)) {
						List<Integer> valuesParsed = parseGeneric(values, errors, value -> Integer.parseInt(value));

						if(!valuesParsed.isEmpty()) {
						    if (values.size() == 1) {
			                    predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
			                } else {
			                    predicates.add(root.get(key).in(valuesParsed));
			                }
						}
					} else if (associatedFieldType.equals(Float.class) || associatedFieldType.equals(float.class)) {
						List<Float> valuesParsed = parseGeneric(values, errors, value -> Float.parseFloat(value));
						if(!valuesParsed.isEmpty()) {
					    if (values.size() == 1) {
		                    predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
		                } else {
		                    predicates.add(root.get(key).in(valuesParsed));
		                }}
					} else if (associatedFieldType.equals(Double.class) || associatedFieldType.equals(double.class)) {
						List<Double> valuesParsed = parseGeneric(values, errors, value -> Double.parseDouble(value));
						if(!valuesParsed.isEmpty()) {
					    if (values.size() == 1) {
		                    predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
		                } else {
		                    predicates.add(root.get(key).in(valuesParsed));
		                }}
					} else if (associatedFieldType.equals(Boolean.class) || associatedFieldType.equals(boolean.class)) {
						if (values.size() == 1) {
		                    predicates.add(cb.equal(root.get(key), Boolean.parseBoolean(values.get(0))));
		                } else {
		                    errors.add(new Exception("no multivalue a boolean"));
		                }
					} else if (associatedFieldType.equals(Long.class) || associatedFieldType.equals(Long.class)) {
						List<Long> valuesParsed = parseGeneric(values, errors, value -> Long.parseLong(value));
						if(!valuesParsed.isEmpty()) {
					    if (values.size() == 1) {
		                    predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
		                } else {
		                    predicates.add(root.get(key).in(valuesParsed));
		                }}
					} else if (associatedFieldType.equals(Date.class)) {
						List<Date> valuesParsed = parseGeneric(values, errors, value -> {
							List<SimpleDateFormat> formatters = Arrays.asList(
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH"),
					            new SimpleDateFormat("yyyy-MM-dd")
					        );
						        
							Date date = null;
			            	for (SimpleDateFormat formatter : formatters) {
			                    try {
			                    	date = formatter.parse(value);
			                        break;
			                    } catch (ParseException ignored) {}
			                }
			            	
			            	if (date==null) {
			                    errors.add(new ParseException("Unparseable date: " + value, 0));
			                }
			            	
			            	return date;
						});
						if(!valuesParsed.isEmpty()) {
			            if (valuesParsed.size() == 1) {
			                predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
			            } else {
			                predicates.add(root.get(key).in(valuesParsed));
			            }}
					} else if (associatedFieldType.equals(java.sql.Date.class)) {
						List<java.sql.Date> valuesParsed = parseGeneric(values, errors, value -> {
							List<SimpleDateFormat> formatters = Arrays.asList(
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm"),
					            new SimpleDateFormat("yyyy-MM-dd'T'HH"),
					            new SimpleDateFormat("yyyy-MM-dd")
					        );
						        
							java.sql.Date date = null;
			            	for (SimpleDateFormat formatter : formatters) {
			                    try {
			                    	date = new java.sql.Date(formatter.parse(value).getTime());
			                        break;
			                    } catch (ParseException ignored) {}
			                }
			            	
			            	if (date==null) {
			                    errors.add(new ParseException("Unparseable date: " + value, 0));
			                }
			            	
			            	return date;
						});
						if(!valuesParsed.isEmpty()) {
			            if (valuesParsed.size() == 1) {
			                predicates.add(cb.equal(root.get(key), valuesParsed.get(0)));
			            } else {
			                predicates.add(root.get(key).in(valuesParsed));
			            }}
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
	
	private <K> List<K> parseGeneric(List<String> values, List<Exception> errors, Function<String, K> convert) {
		List<K> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				list.add(convert.apply(value));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}

	private List<Long> parseToLong(List<String> values, List<Exception> errors) {
		List<Long> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				list.add(Long.parseLong(value));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}

	private List<Double> parseToDouble(List<String> values, List<Exception> errors) {
		List<Double> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				list.add(Double.parseDouble(value));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}

	private List<Float> parseToFloat(List<String> values, List<Exception> errors) {
		List<Float> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				list.add(Float.parseFloat(value));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}

	private List<Integer> parseToInteger(List<String> values, List<Exception> errors) {
		List<Integer> list = new ArrayList<>(values.size()*2);
		
		for (String value : values) {
			try {
				list.add(Integer.parseInt(value));
			} catch (NumberFormatException e) {
				errors.add(e);
			}
		}
		
		return list;
	}
	
	private List<Date> parseToDate(List<String> values, List<Exception> errors) {
        List<Date> parsedDates = new ArrayList<>();
        
        List<SimpleDateFormat> formatters = Arrays.asList(
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm"),
            new SimpleDateFormat("yyyy-MM-dd'T'HH"),
            new SimpleDateFormat("yyyy-MM-dd")
        );
        
        for (String value : values) {
            try {
            	boolean parsed = false;
            	for (SimpleDateFormat formatter : formatters) {
                    try {
                    	parsedDates.add(formatter.parse(value));
                        parsed = true;
                        break;
                    } catch (ParseException ignored) {}
                }
            	
            	if (!parsed) {
                    errors.add(new ParseException("Unparseable date: " + value, 0));
                }
                
            } catch (Exception e) {
                errors.add(e);
            }
        }
        return parsedDates;
    }

    private List<java.sql.Date> parseToSqlDate(List<String> values, List<Exception> errors) {
        List<java.sql.Date> parsedSqlDates = new ArrayList<>();
        
        List<SimpleDateFormat> formatters = Arrays.asList(
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm"),
                new SimpleDateFormat("yyyy-MM-dd'T'HH"),
                new SimpleDateFormat("yyyy-MM-dd")
            );
        
        for (String value : values) {
        	try {
            	boolean parsed = false;
            	for (SimpleDateFormat formatter : formatters) {
                    try {
                    	parsedSqlDates.add(new java.sql.Date(formatter.parse(value).getTime()));
                        parsed = true;
                        break;
                    } catch (ParseException ignored) {}
                }
            	
            	if (!parsed) {
                    errors.add(new ParseException("Unparseable date: " + value, 0));
                }
                
            } catch (Exception e) {
                errors.add(e);
            }
        }
        return parsedSqlDates;
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
