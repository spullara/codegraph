package codegraph;

import org.neo4j.graphdb.RelationshipType;

/**
 * Relationships between nodes.
 *
 * Reference node contains jars.
 * Jar contains packages and classes.
 * Package contains class.
 * Class extends class.
 * Class contains methods.
 * Methods call methods.
 *
 * User: sam
 * Date: 4/8/12
 * Time: 12:47 PM
 */
public enum Relationships implements RelationshipType {
  CALLS,
  EXTENDS,
  IMPLEMENTS,
  CONTAINS
}
