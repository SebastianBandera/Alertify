package app.alertify.jpa.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.util.ReflectionUtils;

import app.alertify.pipes.model.Pipe;

class PipeRepositoryTest {

    @Test
    void detailedEntityGraphUsesExistingModelAttributes() throws Exception {
        Method method = PipeRepository.class.getMethod("findDetailedById", Long.class);
        EntityGraph graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(graph.attributePaths()).allSatisfy(this::assertPathExists);
    }

    private void assertPathExists(String path) {
        Class<?> type = Pipe.class;
        for (String attribute : path.split("\\.")) {
            Field field = ReflectionUtils.findField(type, attribute);
            assertThat(field).as("attribute %s on %s for path %s", attribute, type.getSimpleName(), path).isNotNull();
            if (Collection.class.isAssignableFrom(field.getType()))
                type = ResolvableType.forField(field).as(Collection.class).getGeneric(0).resolve();
            else
                type = field.getType();

            assertThat(type).as("resolved type for %s", path).isNotNull();
        }
    }
}
