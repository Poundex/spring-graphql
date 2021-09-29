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

package org.springframework.graphql.execution;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.SchemaTraverser;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link GraphQlSource.Builder} that initializes a
 * {@link GraphQL} instance and wraps it with a {@link GraphQlSource} that returns it.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 */
class DefaultGraphQlSourceBuilder implements GraphQlSource.Builder {

	private final List<Resource> schemaResources = new ArrayList<>();

	private final List<RuntimeWiringConfigurer> runtimeWiringConfigurers = new ArrayList<>();

	private final List<DataFetcherExceptionResolver> exceptionResolvers = new ArrayList<>();

	private final List<GraphQLTypeVisitor> typeVisitors = new ArrayList<>();

	private final List<Instrumentation> instrumentations = new ArrayList<>();

	private Consumer<GraphQL.Builder> graphQlConfigurers = (builder) -> {
	};
	
	private BiFunction<TypeDefinitionRegistry, RuntimeWiring, GraphQLSchema> schemaFactory =
			(typeRegistry, runtimeWiring) -> new SchemaGenerator().makeExecutableSchema(typeRegistry, runtimeWiring);


	@Override
	public GraphQlSource.Builder schemaResources(Resource... resources) {
		this.schemaResources.addAll(Arrays.asList(resources));
		return this;
	}

	@Override
	public GraphQlSource.Builder configureRuntimeWiring(RuntimeWiringConfigurer configurer) {
		this.runtimeWiringConfigurers.add(configurer);
		return this;
	}

	@Override
	public GraphQlSource.Builder exceptionResolvers(List<DataFetcherExceptionResolver> resolvers) {
		this.exceptionResolvers.addAll(resolvers);
		return this;
	}

	@Override
	public GraphQlSource.Builder typeVisitors(List<GraphQLTypeVisitor> typeVisitors) {
		this.typeVisitors.addAll(typeVisitors);
		return this;
	}

	@Override
	public GraphQlSource.Builder instrumentation(List<Instrumentation> instrumentations) {
		this.instrumentations.addAll(instrumentations);
		return this;
	}

	@Override
	public GraphQlSource.Builder configureGraphQl(Consumer<GraphQL.Builder> configurer) {
		this.graphQlConfigurers = this.graphQlConfigurers.andThen(configurer);
		return this;
	}

	@Override
	public GraphQlSource.Builder schemaFactory(BiFunction<TypeDefinitionRegistry, RuntimeWiring, GraphQLSchema> schemaFactory) {
		this.schemaFactory = schemaFactory;
		return this;
	}

	@Override
	public GraphQlSource build() {
		TypeDefinitionRegistry registry = this.schemaResources.stream()
				.map(this::parseSchemaResource).reduce(TypeDefinitionRegistry::merge)
				.orElseThrow(MissingSchemaException::new);

		RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
		this.runtimeWiringConfigurers.forEach(configurer -> configurer.configure(runtimeWiringBuilder));

		GraphQLSchema schema = schemaFactory.apply(registry, runtimeWiringBuilder.build());
		schema = applyTypeVisitors(schema);

		GraphQL.Builder builder = GraphQL.newGraphQL(schema);
		builder.defaultDataFetcherExceptionHandler(new ExceptionResolversExceptionHandler(this.exceptionResolvers));
		if (!this.instrumentations.isEmpty()) {
			builder = builder.instrumentation(new ChainedInstrumentation(this.instrumentations));
		}

		this.graphQlConfigurers.accept(builder);
		GraphQL graphQl = builder.build();

		return new CachedGraphQlSource(graphQl, schema);
	}

	private TypeDefinitionRegistry parseSchemaResource(Resource schemaResource) {
		Assert.notNull(schemaResource, "'schemaResource' not provided");
		Assert.isTrue(schemaResource.exists(), "'schemaResource' does not exist");
		try {
			try (InputStream inputStream = schemaResource.getInputStream()) {
				return new SchemaParser().parse(inputStream);
			}
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Failed to load schema resource: " + schemaResource.toString());
		}
	}

	private GraphQLSchema applyTypeVisitors(GraphQLSchema schema) {
		List<GraphQLTypeVisitor> visitors = new ArrayList<>(this.typeVisitors);
		visitors.add(ContextDataFetcherDecorator.TYPE_VISITOR);

		GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
		Map<Class<?>, Object> vars = Collections.singletonMap(GraphQLCodeRegistry.Builder.class, codeRegistry);

		SchemaTraverser traverser = new SchemaTraverser();
		traverser.depthFirstFullSchema(visitors, schema, vars);

		return schema.transformWithoutTypes(builder -> builder.codeRegistry(codeRegistry));
	}



	/**
	 * GraphQlSource that returns the built GraphQL instance and its schema.
	 */
	private static class CachedGraphQlSource implements GraphQlSource {

		private final GraphQL graphQl;

		private final GraphQLSchema schema;

		CachedGraphQlSource(GraphQL graphQl, GraphQLSchema schema) {
			this.graphQl = graphQl;
			this.schema = schema;
		}

		@Override
		public GraphQL graphQl() {
			return this.graphQl;
		}

		@Override
		public GraphQLSchema schema() {
			return this.schema;
		}

	}

}
