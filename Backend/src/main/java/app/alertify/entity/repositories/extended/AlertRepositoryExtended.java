package app.alertify.entity.repositories.extended;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.Alert;
import app.alertify.entity.repositories.AlertRepository;
import app.alertify.entity.repositories.custom.DynamicSearchAlert;
import app.alertify.entity.repositories.custom.DynamicSearchResult;

@Repository
public class AlertRepositoryExtended implements AlertRepository {
	
	@Autowired
	private AlertRepository alertRepository;
	
	@Autowired
	private DynamicSearchAlert dynamicSearch;

	public Page<Alert> findByActiveTrue(Pageable pageable) {
		return alertRepository.findByActiveTrue(pageable);
	}

	public Page<Alert> findAlertsNotInAnyGroup(Pageable pageable) {
		return alertRepository.findAlertsNotInAnyGroup(pageable);
	}

	public <S extends Alert> S save(S entity) {
		return alertRepository.save(entity);
	}

	public <S extends Alert> Optional<S> findOne(Example<S> example) {
		return alertRepository.findOne(example);
	}

	public List<Alert> findAll() {
		return alertRepository.findAll();
	}

	public Page<Alert> findAll(Pageable pageable) {
		return alertRepository.findAll(pageable);
	}

	public List<Alert> findAll(Sort sort) {
		return alertRepository.findAll(sort);
	}

	public List<Alert> findAllById(Iterable<Long> ids) {
		return alertRepository.findAllById(ids);
	}

	public <S extends Alert> List<S> saveAll(Iterable<S> entities) {
		return alertRepository.saveAll(entities);
	}

	public void flush() {
		alertRepository.flush();
	}

	public <S extends Alert> S saveAndFlush(S entity) {
		return alertRepository.saveAndFlush(entity);
	}

	public <S extends Alert> List<S> saveAllAndFlush(Iterable<S> entities) {
		return alertRepository.saveAllAndFlush(entities);
	}

	public <S extends Alert> Page<S> findAll(Example<S> example, Pageable pageable) {
		return alertRepository.findAll(example, pageable);
	}

	public Optional<Alert> findById(Long id) {
		return alertRepository.findById(id);
	}

	@Deprecated
	public void deleteInBatch(Iterable<Alert> entities) {
		alertRepository.deleteInBatch(entities);
	}

	public boolean existsById(Long id) {
		return alertRepository.existsById(id);
	}

	public <S extends Alert> long count(Example<S> example) {
		return alertRepository.count(example);
	}

	public void deleteAllInBatch(Iterable<Alert> entities) {
		alertRepository.deleteAllInBatch(entities);
	}

	public <S extends Alert> boolean exists(Example<S> example) {
		return alertRepository.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		alertRepository.deleteAllByIdInBatch(ids);
	}

	public <S extends Alert, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return alertRepository.findBy(example, queryFunction);
	}

	public long count() {
		return alertRepository.count();
	}

	public void deleteAllInBatch() {
		alertRepository.deleteAllInBatch();
	}

	public void deleteById(Long id) {
		alertRepository.deleteById(id);
	}

	@Deprecated
	public Alert getOne(Long id) {
		return alertRepository.getOne(id);
	}

	public void delete(Alert entity) {
		alertRepository.delete(entity);
	}

	@Deprecated
	public Alert getById(Long id) {
		return alertRepository.getById(id);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		alertRepository.deleteAllById(ids);
	}

	public void deleteAll(Iterable<? extends Alert> entities) {
		alertRepository.deleteAll(entities);
	}

	public Alert getReferenceById(Long id) {
		return alertRepository.getReferenceById(id);
	}

	public void deleteAll() {
		alertRepository.deleteAll();
	}

	public <S extends Alert> List<S> findAll(Example<S> example) {
		return alertRepository.findAll(example);
	}

	public <S extends Alert> List<S> findAll(Example<S> example, Sort sort) {
		return alertRepository.findAll(example, sort);
	}

	public DynamicSearchResult<Alert> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<Alert> type, List<BiFunction<Root<Alert>, CriteriaQuery<Alert>, Predicate>> fixedPredicates) {
		return dynamicSearch.customSearch(pageable, params, type, fixedPredicates);
	}
}
