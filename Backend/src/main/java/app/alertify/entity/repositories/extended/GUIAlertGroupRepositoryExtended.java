package app.alertify.entity.repositories.extended;

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

import app.alertify.entity.GUIAlertGroup;
import app.alertify.entity.repositories.GUIAlertGroupRepository;
import app.alertify.entity.repositories.custom.DynamicSearchResult;
import app.alertify.entity.repositories.custom.instances.DynamicSearchGUIAlertGroupRepository;

@Repository
public class GUIAlertGroupRepositoryExtended implements GUIAlertGroupRepository {
	
	@Autowired
	private GUIAlertGroupRepository repository;
	
	@Autowired
	private DynamicSearchGUIAlertGroupRepository dynamicSearch;

	public Page<GUIAlertGroup> findByActiveTrue(Pageable pageable) {
		return repository.findByActiveTrue(pageable);
	}

	public <S extends GUIAlertGroup> S save(S entity) {
		return repository.save(entity);
	}

	public <S extends GUIAlertGroup> Optional<S> findOne(Example<S> example) {
		return repository.findOne(example);
	}

	public List<GUIAlertGroup> findAll() {
		return repository.findAll();
	}

	public Page<GUIAlertGroup> findAll(Pageable pageable) {
		return repository.findAll(pageable);
	}

	public List<GUIAlertGroup> findAll(Sort sort) {
		return repository.findAll(sort);
	}

	public List<GUIAlertGroup> findAllById(Iterable<Long> ids) {
		return repository.findAllById(ids);
	}

	public <S extends GUIAlertGroup> List<S> saveAll(Iterable<S> entities) {
		return repository.saveAll(entities);
	}

	public void flush() {
		repository.flush();
	}

	public <S extends GUIAlertGroup> S saveAndFlush(S entity) {
		return repository.saveAndFlush(entity);
	}

	public <S extends GUIAlertGroup> List<S> saveAllAndFlush(Iterable<S> entities) {
		return repository.saveAllAndFlush(entities);
	}

	public <S extends GUIAlertGroup> Page<S> findAll(Example<S> example, Pageable pageable) {
		return repository.findAll(example, pageable);
	}

	public Optional<GUIAlertGroup> findById(Long id) {
		return repository.findById(id);
	}

	@Deprecated
	public void deleteInBatch(Iterable<GUIAlertGroup> entities) {
		repository.deleteInBatch(entities);
	}

	public boolean existsById(Long id) {
		return repository.existsById(id);
	}

	public <S extends GUIAlertGroup> long count(Example<S> example) {
		return repository.count(example);
	}

	public void deleteAllInBatch(Iterable<GUIAlertGroup> entities) {
		repository.deleteAllInBatch(entities);
	}

	public <S extends GUIAlertGroup> boolean exists(Example<S> example) {
		return repository.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		repository.deleteAllByIdInBatch(ids);
	}

	public <S extends GUIAlertGroup, R> R findBy(Example<S> example,
			Function<FetchableFluentQuery<S>, R> queryFunction) {
		return repository.findBy(example, queryFunction);
	}

	public long count() {
		return repository.count();
	}

	public void deleteAllInBatch() {
		repository.deleteAllInBatch();
	}

	public void deleteById(Long id) {
		repository.deleteById(id);
	}

	@Deprecated
	public GUIAlertGroup getOne(Long id) {
		return repository.getOne(id);
	}

	public void delete(GUIAlertGroup entity) {
		repository.delete(entity);
	}

	@Deprecated
	public GUIAlertGroup getById(Long id) {
		return repository.getById(id);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		repository.deleteAllById(ids);
	}

	public void deleteAll(Iterable<? extends GUIAlertGroup> entities) {
		repository.deleteAll(entities);
	}

	public GUIAlertGroup getReferenceById(Long id) {
		return repository.getReferenceById(id);
	}

	public void deleteAll() {
		repository.deleteAll();
	}

	public <S extends GUIAlertGroup> List<S> findAll(Example<S> example) {
		return repository.findAll(example);
	}

	public <S extends GUIAlertGroup> List<S> findAll(Example<S> example, Sort sort) {
		return repository.findAll(example, sort);
	}

	public DynamicSearchResult<GUIAlertGroup> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<GUIAlertGroup> type) {
		return dynamicSearch.customSearch(pageable, params, type);
	}
}
