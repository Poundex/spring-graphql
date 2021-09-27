/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.graphql.data.method.annotation.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import graphql.ExecutionResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.graphql.GraphQlService;
import org.springframework.graphql.RequestInput;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.DefaultBatchLoaderRegistry;
import org.springframework.graphql.execution.ExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test GraphQL requests handled through {@code @BatchMapping} methods.
 *
 * @author Rossen Stoyanchev
 */
@SuppressWarnings({"unchecked", "unused"})
public class BatchMappingInvocationTests {

	private static final Map<Long, Course> courseMap = new HashMap<>();

	private static final Map<Long, Person> personMap = new HashMap<>();

	static {
		Course.save(11L, "Ethical Hacking", 15L, Arrays.asList(22L, 26L, 31L));
		Course.save(19L, "Docker and Kubernetes", 17L, Arrays.asList(31L, 39L, 44L, 45L));

		Person.save(15L, "Josh", "Kelly");
		Person.save(17L, "Albert", "Murray");
		Person.save(22L, "Bonnie", "Gray");
		Person.save(26L, "John", "Perry");
		Person.save(31L, "Alaine", "Baily");
		Person.save(39L, "Jeff", "Peterson");
		Person.save(44L, "Jared", "Mccarthy");
		Person.save(45L, "Benjamin", "Brown");
	}

	private static final String schema = "" +
			"type Query {" +
			"    courses: [Course]" +
			"}" +
			"type Course {" +
			"    id: ID" +
			"    name: String" +
			"    instructor: Person" +
			"    students: [Person]" +
			"}" +
			"type Person {" +
			"    id: ID" +
			"    firstName: String" +
			"    lastName: String" +
			"}";


	private static Class<?>[] controllerClasses() {
		return new Class[] {
				BatchFluxController.class,
				BatchListController.class,
				BatchMonoMapController.class,
				BatchMapController.class
		};
	}

	@ParameterizedTest
	@MethodSource("controllerClasses")
	void oneToOne(Class<?> controllerClass) {
		String query = "{ " +
				"  courses { " +
				"    name" +
				"    instructor {" +
				"      firstName" +
				"      lastName" +
				"    }" +
				"  }" +
				"}";

		ExecutionResult result = initGraphQlService(controllerClass, CourseConfig.class)
				.execute(new RequestInput(query, null, null))
				.block();

		Map<String, Object> data = getData(result);
		List<Map<String, Object>> actualCourses = (List<Map<String, Object>>) data.get("courses");

		List<Course> courses = Course.allCourses();
		assertThat(actualCourses).hasSize(courses.size());

		for (int i = 0; i < courses.size(); i++) {
			Map<String, Object> actualCourse = actualCourses.get(i);
			Course course = courses.get(i);
			assertThat(actualCourse.get("name")).isEqualTo(course.name());

			Map<String, Object> actualInstructor = (Map<String, Object>) actualCourse.get("instructor");
			assertThat(actualInstructor.get("firstName")).isEqualTo(course.instructor().firstName());
			assertThat(actualInstructor.get("lastName")).isEqualTo(course.instructor().lastName());
		}
	}

	@ParameterizedTest
	@MethodSource("controllerClasses")
	void oneToMany(Class<?> controllerClass) {
		String query = "{ " +
				"  courses { " +
				"    name" +
				"    students {" +
				"      firstName" +
				"      lastName" +
				"    }" +
				"  }" +
				"}";

		ExecutionResult result = initGraphQlService(controllerClass, CourseConfig.class)
				.execute(new RequestInput(query, null, null))
				.block();

		Map<String, Object> data = getData(result);
		List<Map<String, Object>> actualCourses = (List<Map<String, Object>>) data.get("courses");

		List<Course> courses = Course.allCourses();
		assertThat(actualCourses).hasSize(courses.size());

		for (int i = 0; i < courses.size(); i++) {
			Map<String, Object> actualCourse = actualCourses.get(i);
			Course course = courses.get(i);
			assertThat(actualCourse.get("name")).isEqualTo(course.name());

			List<Map<String, Object>> actualStudents = (List<Map<String, Object>>) actualCourse.get("students");
			List<Person> students = course.students();
			assertThat(actualStudents).hasSize(students.size());

			for (int j = 0; j < actualStudents.size(); j++) {
				assertThat(actualStudents.get(i).get("firstName")).isEqualTo(students.get(i).firstName());
				assertThat(actualStudents.get(i).get("lastName")).isEqualTo(students.get(i).lastName());
			}
		}
	}

	private ExecutionGraphQlService initGraphQlService(Class<?>... configClasses) {
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
		applicationContext.register(configClasses);
		applicationContext.refresh();

		return applicationContext.getBean(ExecutionGraphQlService.class);
	}

	private <T> T getData(@Nullable ExecutionResult result) {
		assertThat(result).isNotNull();
		assertThat(result.getErrors()).isEmpty();
		T data = result.getData();
		assertThat(data).isNotNull();
		return data;
	}


	private static class CourseController {

		@QueryMapping
		public Collection<Course> courses() {
			return courseMap.values();
		}
	}

	@Controller
	private static class BatchFluxController extends CourseController {

		@BatchMapping
		public Flux<Person> instructor(List<Course> courses) {
			return Flux.fromIterable(courses).map(Course::instructor);
		}

		@BatchMapping
		public Flux<List<Person>> students(List<Course> courses) {
			return Flux.fromIterable(courses).map(Course::students);
		}
	}

	@Controller
	private static class BatchListController extends CourseController {

		@BatchMapping
		public List<Person> instructor(List<Course> courses) {
			return courses.stream().map(Course::instructor).collect(Collectors.toList());
		}

		@BatchMapping
		public List<List<Person>> students(List<Course> courses) {
			return courses.stream().map(Course::students).collect(Collectors.toList());
		}
	}

	@Controller
	private static class BatchMonoMapController extends CourseController {

		@BatchMapping
		public Mono<Map<Course, Person>> instructor(List<Course> courses) {
			return Flux.fromIterable(Course.allCourses())
					.collect(Collectors.toMap(Function.identity(), Course::instructor));
		}

		@BatchMapping
		public Mono<Map<Course, List<Person>>> students(Set<Course> courses) {
			return Flux.fromIterable(courses).collect(Collectors.toMap(Function.identity(), Course::students));
		}
	}

	@Controller
	private static class BatchMapController extends CourseController {

		@BatchMapping
		public Map<Course, Person> instructor(List<Course> courses) {
			return Course.allCourses().stream().collect(
					Collectors.toMap(Function.identity(), Course::instructor));
		}

		@BatchMapping
		public Map<Course, List<Person>> students(List<Course> courses) {
			return courses.stream().collect(Collectors.toMap(Function.identity(), Course::students));
		}
	}


	private static class CourseConfig {

		@Bean
		public GraphQlSource graphQlSource(AnnotatedDataFetcherConfigurer configurer) {
			return GraphQlSource.builder()
					.schemaResources(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)))
					.configureRuntimeWiring(configurer)
					.build();
		}

		@Bean
		public GraphQlService graphQlService(GraphQlSource source, BatchLoaderRegistry registry) {
			ExecutionGraphQlService service = new ExecutionGraphQlService(source);
			service.addDataLoaderRegistrar(registry);
			return service;
		}

		@Bean
		public AnnotatedDataFetcherConfigurer annotatedDataFetcherConfigurer() {
			return new AnnotatedDataFetcherConfigurer();
		}

		@Bean
		public BatchLoaderRegistry batchLoaderRegistry() {
			return new DefaultBatchLoaderRegistry();
		}
	}


	private static class Course {

		private final Long id;

		private final String name;

		private final Long instructorId;

		private final List<Long> studentIds;

		public Course(Long id, String name, Long instructorId, List<Long> studentIds) {
			this.id = id;
			this.name = name;
			this.instructorId = instructorId;
			this.studentIds = studentIds;
		}

		public String name() {
			return this.name;
		}

		public Long instructorId() {
			return this.instructorId;
		}

		public List<Long> studentIds() {
			return this.studentIds;
		}

		public List<Person> students() {
			return this.studentIds.stream().map(personMap::get).collect(Collectors.toList());
		}

		public Person instructor() {
			return personMap.get(this.instructorId);
		}

		public static void save(Long id, String name, Long instructorId, List<Long> studentIds) {
			Course course = new Course(id, name, instructorId, studentIds);
			courseMap.put(id, course);
		}

		public static List<Course> allCourses() {
			return new ArrayList<>(courseMap.values());
		}

		// Course is a key in the DataLoader map

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (other == null || getClass() != other.getClass()) {
				return false;
			}
			return this.id.equals(((Course) other).id);
		}

		@Override
		public int hashCode() {
			return this.id.hashCode();
		}
	}

	private static class Person {

		private final Long id;

		private final String firstName;

		private final String lastName;

		public Person(Long id, String firstName, String lastName) {
			this.id = id;
			this.firstName = firstName;
			this.lastName = lastName;
		}

		public Long id() {
			return this.id;
		}

		public String firstName() {
			return this.firstName;
		}

		public String lastName() {
			return this.lastName;
		}

		public static void save(Long id, String firstName, String lastName) {
			Person person = new Person(id, firstName, lastName);
			personMap.put(id, person);
		}
	}

}
