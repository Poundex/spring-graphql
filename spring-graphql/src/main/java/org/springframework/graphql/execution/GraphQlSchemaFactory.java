package org.springframework.graphql.execution;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeDefinitionRegistry;

public interface GraphQlSchemaFactory {
    GraphQLSchema getSchema(TypeDefinitionRegistry typeDefinitionRegistry, RuntimeWiring runtimeWiring);
}
