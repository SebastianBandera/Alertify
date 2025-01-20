package app.alertify.entity.repositories.extended;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.repositories.AlertResultRepository;
import app.alertify.entity.repositories.custom.DynamicSearchResult;
import app.alertify.entity.repositories.custom.instances.DynamicSearchAlertResult;

@Repository
public class AlertResultRepositoryExtended implements AlertResultRepository {
	
	@Autowired
	private AlertResultRepository alertResultsRepository;
	
	@Autowired
	private DynamicSearchAlertResult dynamicSearchAlertResult;

	public Page<AlertResult> findByActiveTrue(Pageable pageable) {
		return alertResultsRepository.findByActiveTrue(pageable);
	}

	public Page<AlertResult> getAlertsResultByAlert(Alert alert, boolean needsReview, Pageable pageable) {
		return alertResultsRepository.getAlertsResultByAlert(alert, needsReview, pageable);
	}

	public <S extends AlertResult> S save(S entity) {
		return alertResultsRepository.save(entity);
	}

	public <S extends AlertResult> Optional<S> findOne(Example<S> example) {
		return alertResultsRepository.findOne(example);
	}

	public List<AlertResult> findAll() {
		return alertResultsRepository.findAll();
	}

	public Page<AlertResult> findAll(Pageable pageable) {
		return alertResultsRepository.findAll(pageable);
	}

	public List<AlertResult> findAll(Sort sort) {
		return alertResultsRepository.findAll(sort);
	}

	public List<AlertResult> findAllById(Iterable<Long> ids) {
		return alertResultsRepository.findAllById(ids);
	}

	public <S extends AlertResult> List<S> saveAll(Iterable<S> entities) {
		return alertResultsRepository.saveAll(entities);
	}

	public void flush() {
		alertResultsRepository.flush();
	}

	public <S extends AlertResult> S saveAndFlush(S entity) {
		return alertResultsRepository.saveAndFlush(entity);
	}

	public <S extends AlertResult> List<S> saveAllAndFlush(Iterable<S> entities) {
		return alertResultsRepository.saveAllAndFlush(entities);
	}

	public <S extends AlertResult> Page<S> findAll(Example<S> example, Pageable pageable) {
		return alertResultsRepository.findAll(example, pageable);
	}

	public Optional<AlertResult> findById(Long id) {
		return alertResultsRepository.findById(id);
	}

	@Deprecated
	public void deleteInBatch(Iterable<AlertResult> entities) {
		alertResultsRepository.deleteInBatch(entities);
	}

	public boolean existsById(Long id) {
		return alertResultsRepository.existsById(id);
	}

	public <S extends AlertResult> long count(Example<S> example) {
		return alertResultsRepository.count(example);
	}

	public void deleteAllInBatch(Iterable<AlertResult> entities) {
		alertResultsRepository.deleteAllInBatch(entities);
	}

	public <S extends AlertResult> boolean exists(Example<S> example) {
		return alertResultsRepository.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		alertResultsRepository.deleteAllByIdInBatch(ids);
	}

	public <S extends AlertResult, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return alertResultsRepository.findBy(example, queryFunction);
	}

	public long count() {
		return alertResultsRepository.count();
	}

	public void deleteAllInBatch() {
		alertResultsRepository.deleteAllInBatch();
	}

	public void deleteById(Long id) {
		alertResultsRepository.deleteById(id);
	}

	@Deprecated
	public AlertResult getOne(Long id) {
		return alertResultsRepository.getOne(id);
	}

	public void delete(AlertResult entity) {
		alertResultsRepository.delete(entity);
	}

	@Deprecated
	public AlertResult getById(Long id) {
		return alertResultsRepository.getById(id);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		alertResultsRepository.deleteAllById(ids);
	}

	public void deleteAll(Iterable<? extends AlertResult> entities) {
		alertResultsRepository.deleteAll(entities);
	}

	public AlertResult getReferenceById(Long id) {
		return alertResultsRepository.getReferenceById(id);
	}

	public void deleteAll() {
		alertResultsRepository.deleteAll();
	}

	public <S extends AlertResult> List<S> findAll(Example<S> example) {
		return alertResultsRepository.findAll(example);
	}

	public <S extends AlertResult> List<S> findAll(Example<S> example, Sort sort) {
		return alertResultsRepository.findAll(example, sort);
	}

	public DynamicSearchResult<AlertResult> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<AlertResult> type) {
		return dynamicSearchAlertResult.customSearch(pageable, params, type);
	}

	@Override
	public Date findLastDateAlertResultByAlert(Alert alert) {
		return alertResultsRepository.findLastDateAlertResultByAlert(alert);
	}

	public Date findLastDateAlertResultByAlertId(Long alertId) {
		Alert alert = new Alert();
		alert.setId(alertId);
		return alertResultsRepository.findLastDateAlertResultByAlert(alert);
	}
}
