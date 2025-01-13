package app.alertify.entity.repositories.custom;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.data.domain.Page;
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
	public Page<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(type);
        Root<T> root = query.from(type);
        
        List<Predicate> predicates = new ArrayList<>();
        
        params.forEach((key, values) -> {
            if (values.size() == 1) {
                predicates.add(cb.equal(root.get(key), Boolean.parseBoolean(values.get(0))));
            } else {
                predicates.add(root.get(key).in(values));
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

        return new PageImpl<>(results, pageable, total);
	}
}
