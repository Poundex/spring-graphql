package org.springframework.graphql.execution;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;

public class DefaultGraphQlSchemaFactory implements GraphQlSchemaFactory {

    @Override
    public GraphQLSchema getSchema(TypeDefinitionRegistry typeDefinitionRegistry, RuntimeWiring runtimeWiring) {
        return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
    }
}
