package apoc.refactor;

import apoc.util.ArrayBackedList;
import apoc.util.TestUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.*;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.*;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testCallEmpty;
import static apoc.util.TestUtil.testResult;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;

/**
 * @author mh
 * @since 25.03.16
 */
public class GraphRefactoringTest {

    private GraphDatabaseService db;

    @Before
    public void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        TestUtil.registerProcedure(db, GraphRefactoring.class);
    }

    /*
        MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) call apoc.refactor.mergeNodes([o,n]) yield node return node
     */
    @After
    public void tearDown() {
        db.shutdown();
    }


    @Test
    public void testDeleteOneNode() throws Exception {
        long id = db.execute("CREATE (p1:Person {ID:1}), (p2:Person {ID:2}) RETURN id(p1) as id ").<Long>columnAs("id").next();
        ExecutionPlanDescription plan = db.execute("EXPLAIN MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) DELETE o RETURN o as node").getExecutionPlanDescription();
        System.out.println(plan);
        System.out.flush();
        testCall(db, "MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) DELETE o RETURN n as node",
                      map("oldID", 1L, "newID",2L),
                (r) -> {
                    Node node = (Node) r.get("node");
                    assertNotEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Person")));
                    assertEquals(2L, node.getProperty("ID"));
                });
    }

    @Test
    public void testEagernessMergeNodesFails() throws Exception {
        db.execute("CREATE INDEX ON :Person(ID)").close();
        long id = db.execute("CREATE (p1:Person {ID:1}), (p2:Person {ID:2}) RETURN id(p1) as id ").<Long>columnAs("id").next();
        ExecutionPlanDescription plan = db.execute("EXPLAIN MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) CALL apoc.refactor.mergeNodes([o,n]) yield node return node").getExecutionPlanDescription();
        System.out.println(plan);
        System.out.flush();
        testCall(db, "MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) CALL apoc.refactor.mergeNodes([o,n]) yield node return node",
                      map("oldID", 1L, "newID",2L),
                (r) -> {
                    Node node = (Node) r.get("node");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Person")));
                    assertEquals(2L, node.getProperty("ID"));
                });
    }

    @Test
    public void testMergeNodesEagerAggregation() throws Exception {
        long id = db.execute("CREATE (p1:Person {ID:1}), (p2:Person {ID:2}) RETURN id(p1) as id ").<Long>columnAs("id").next();
        ExecutionPlanDescription plan = db.execute("EXPLAIN MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) WITH head(collect([o,n])) as nodes CALL apoc.refactor.mergeNodes(nodes) yield node return node").getExecutionPlanDescription();
        System.out.println(plan);
        System.out.flush();
        testCall(db, "MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) WITH head(collect([o,n])) as nodes CALL apoc.refactor.mergeNodes(nodes) yield node return node",
                      map("oldID", 1L, "newID",2L),
                (r) -> {
                    Node node = (Node) r.get("node");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Person")));
                    assertEquals(2L, node.getProperty("ID"));
                });
    }

    @Test
    public void testMergeNodesEagerIndex() throws Exception {
        db.execute("CREATE INDEX ON :Person(ID)").close();
        long id = db.execute("CREATE (p1:Person {ID:1}), (p2:Person {ID:2}) RETURN id(p1) as id ").<Long>columnAs("id").next();
        ExecutionPlanDescription plan = db.execute("EXPLAIN MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) USING INDEX o:Person(ID) USING INDEX n:Person(ID) CALL apoc.refactor.mergeNodes([o,n]) yield node return node").getExecutionPlanDescription();
        System.out.println(plan);
        System.out.flush();
        testCall(db, "MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) USING INDEX o:Person(ID) USING INDEX n:Person(ID) CALL apoc.refactor.mergeNodes([o,n]) yield node return node",
                      map("oldID", 1L, "newID",2L),
                (r) -> {
                    Node node = (Node) r.get("node");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Person")));
                    assertEquals(2L, node.getProperty("ID"));
                });
    }

    /*
    ISSUE #590
     */
    @Test
    public void testMergeMultipleNodesRelationshipDirection() {
        db.execute("create (a1:ALabel {name:'a1'})-[:HAS_REL]->(b1:BLabel {name:'b1'})," +
                "          (a2:ALabel {name:'a2'})-[:HAS_REL]->(b2:BLabel {name:'b2'})," +
                "          (a3:ALabel {name:'a3'})-[:HAS_REL]->(b3:BLabel {name:'b3'}), " +
                "          (a4:ALabel {name:'a4'})-[:HAS_REL]->(b4:BLabel {name:'b4'})");
        Map<String,Object> params = null;

        testCall(db, "MATCH (b1:BLabel {name:'b1'}), (b2:BLabel {name:'b2'}), (b3:BLabel {name:'b3'}), (b4:BLabel {name:'b4'}) " +
                "     WITH head(collect([b1,b2,b3,b4])) as nodes CALL apoc.refactor.mergeNodes(nodes) yield node return node", params,
                row -> {
                    assertTrue(row.get("node") != null);
                    assertTrue(row.get("node") instanceof Node);
                    Node resultingNode = (Node)(row.get("node"));
                    assertTrue(resultingNode.getDegree(Direction.OUTGOING) == 0);
                    assertTrue(resultingNode.getDegree(Direction.INCOMING) == 4);
                }
        );
    }

    @Test
    public void testMergeNodesWithNonDistinct() {
        db.execute("create (a1:ALabel {name:'a1'})-[:HAS_REL]->(b1:BLabel {name:'b1'})," +
                "          (a2:ALabel {name:'a2'})-[:HAS_REL]->(b2:BLabel {name:'b2'})," +
                "          (a3:ALabel {name:'a3'})-[:HAS_REL]->(b3:BLabel {name:'b3'}) ");

        testCall(db, "MATCH (a1:ALabel{name:'a1'}),(a2:ALabel{name:'a2'}),(a3:ALabel{name:'a3'}) " +
                //                 | here we're using a2 two times!
                //                \/
                        "WITH [a1,a2,a2,a3] as nodes limit 1 " +
                        "CALL apoc.refactor.mergeNodes(nodes) yield node return node",
                row -> {
                    Node node = (Node) row.get("node");
                    assertNotNull(node);
                    assertTrue(node.getDegree(Direction.OUTGOING) == 3);
                    assertTrue(node.getDegree(Direction.INCOMING) == 0);
                }
        );

        testResult(db, "MATCH (a:ALabel) return count(*) as count", result -> {
            assertEquals( "other ALabel nodes have been deleted", 1, (long)Iterators.single(result.columnAs("count")));
        });
    }

    @Test
    public void testMergeNodesOneSingleNode() {
        db.execute("create (a1:ALabel {name:'a1'})-[:HAS_REL]->(b1:BLabel {name:'b1'})");
        testCall(db, "MATCH (a1:ALabel{name:'a1'}) " +
                        "WITH a1 limit 1 " +
                        "CALL apoc.refactor.mergeNodes([a1]) yield node return node",
                row -> {
                    Node node = (Node) row.get("node");
                    assertNotNull(node);
                    assertTrue(node.getDegree(Direction.OUTGOING) == 1);
                    assertTrue(node.getDegree(Direction.INCOMING) == 0);
                }
        );
    }

    @Test
    public void testMergeNodesIsTolerantForDeletedNodes() {
        db.execute("create (a1:ALabel {name:'a1'})-[:HAS_REL]->(b1:BLabel {name:'b1'})," +
                "(a2:ALabel {name:'a2'}), " +
                "(a3:ALabel {name:'a3'})-[:HAS_REL]->(b1)");
        testCall(db, "MATCH (a1:ALabel{name:'a1'}), (a2:ALabel{name:'a2'}), (a3:ALabel{name:'a3'}) " +
                        "WITH a1,a2,a3 limit 1 " +
                        "DELETE a2 " +
                        "WITH a1, a2, a3 " +
                        "CALL apoc.refactor.mergeNodes([a1,a2,a3]) yield node return node",
                row -> {
                    Node node = (Node) row.get("node");
                    assertNotNull(node);
                    assertTrue(node.getDegree(Direction.OUTGOING) == 2);
                    assertTrue(node.getDegree(Direction.INCOMING) == 0);
                }
        );
    }

    @Test
    public void testExtractNode() throws Exception {
        Long id = db.execute("CREATE (f:Foo)-[rel:FOOBAR {a:1}]->(b:Bar) RETURN id(rel) as id").<Long>columnAs("id").next();
        testCall(db, "CALL apoc.refactor.extractNode({ids},['FooBar'],'FOO','BAR')", map("ids", singletonList(id)),
                (r) -> {
                    assertEquals(id, r.get("input"));
                    Node node = (Node) r.get("output");
                    assertEquals(true, node.hasLabel(Label.label("FooBar")));
                    assertEquals(1L, node.getProperty("a"));
                    assertNotNull(node.getSingleRelationship(RelationshipType.withName("FOO"), Direction.OUTGOING));
                    assertNotNull(node.getSingleRelationship(RelationshipType.withName("BAR"), Direction.INCOMING));
                });
    }
    @Test
    public void testInvertRelationship() throws Exception {
        ResourceIterator<Long> it = db.execute("CREATE (f:Foo)-[rel:FOOBAR {a:1}]->(b:Bar) RETURN id(rel) as id").<Long>columnAs("id");
        Long id = it.next();
        it.close();
        testCall(db, "MATCH ()-[r]->() WHERE id(r) = {id} CALL apoc.refactor.invert(r) yield input, output RETURN *", map("id", id),
                (r) -> {
                    assertEquals(id, r.get("input"));
                    Relationship rel = (Relationship) r.get("output");
                    assertEquals(true, rel.getStartNode().hasLabel(Label.label("Bar")));
                    assertEquals(true, rel.getEndNode().hasLabel(Label.label("Foo")));
                    assertEquals(1L, rel.getProperty("a"));
                });
    }

    @Test
    public void testCollapseNode() throws Exception {
        Long id = db.execute("CREATE (f:Foo)-[:FOO {a:1}]->(b:Bar {c:3})-[:BAR {b:2}]->(f) RETURN id(b) as id").<Long>columnAs("id").next();
        testCall(db, "CALL apoc.refactor.collapseNode({ids},'FOOBAR')", map("ids", singletonList(id)),
                (r) -> {
                    assertEquals(id, r.get("input"));
                    Relationship rel = (Relationship) r.get("output");
                    assertEquals(true, rel.isType(RelationshipType.withName("FOOBAR")));
                    assertEquals(1L, rel.getProperty("a"));
                    assertEquals(2L, rel.getProperty("b"));
                    assertEquals(3L, rel.getProperty("c"));
                    assertNotNull(rel.getEndNode().hasLabel(Label.label("Foo")));
                    assertNotNull(rel.getStartNode().hasLabel(Label.label("Foo")));
                });
    }

    @Test
    public void testNormalizeAsBoolean() throws Exception {
        db.execute("CREATE ({prop: 'Y', id:1}),({prop: 'Yes', id: 2}),({prop: 'NO', id: 3}),({prop: 'X', id: 4})").close();

        testResult(
            db,
            "MATCH (n) CALL apoc.refactor.normalizeAsBoolean(n,'prop',['Y','Yes'],['NO']) WITH n ORDER BY n.id RETURN n.prop AS prop",
            (r) -> {
                List<Boolean> result = new ArrayList<>();
                while (r.hasNext())
                    result.add((Boolean) r.next().get("prop"));
                assertThat(result, equalTo(Arrays.asList(true, true, false, null)));
            }
        );
    }

    private void categorizeWithDirection(Direction direction) {
        db.execute(
                "CREATE ({prop: 'A', k: 'a', id: 1}) " +
                        "CREATE ({prop: 'A', k: 'a', id: 2}) " +
                        "CREATE ({prop: 'C', k: 'c', id: 3}) " +
                        "CREATE ({                   id: 4}) " +
                        "CREATE ({prop: 'B', k: 'b', id: 5}) " +
                        "CREATE ({prop: 'C', k: 'c', id: 6})").close();


        final boolean outgoing = direction == Direction.OUTGOING ? true : false;
        testCallEmpty(
                db,
                "CALL apoc.refactor.categorize('prop','IS_A', $direction, 'Letter','name',['k'],1)",
                map("direction", outgoing)
        );

        String traversePattern = (outgoing ? "" : "<") + "-[:IS_A]-" + (outgoing ? ">" : "");
        {
            Result result = db.execute("MATCH (n) WITH n ORDER BY n.id MATCH (n)" + traversePattern + "(cat:Letter) RETURN collect(cat.name) AS cats");
            List<?> cats = (List<?>) result.next().get("cats");
            result.close();

            assertThat(cats, equalTo(asList("A", "A", "C", "B", "C")));
        }

        {
            Result result = db.execute("MATCH (n) WITH n ORDER BY n.id MATCH (n)" + traversePattern + "(cat:Letter) RETURN collect(cat.k) AS cats");
            List<?> cats = (List<?>) result.next().get("cats");
            result.close();

            assertThat(cats, equalTo(asList("a", "a", "c", "b", "c")));
        }

        testCall(db, "MATCH (n) WHERE n.prop IS NOT NULL RETURN count(n) AS count", (r) -> assertThat(((Number)r.get("count")).longValue(), equalTo(0L)));
    }

    @Test
    public void testCategorizeOutgoing() throws Exception {
        categorizeWithDirection(Direction.OUTGOING);
    }

    @Test
    public void testCategorizeIncoming() throws Exception {
        categorizeWithDirection(Direction.INCOMING);
    }

    @Test
    public void testCloneNodes() throws Exception {
        Node node = db.execute("CREATE (f:Foo {name:'foo',age:42})-[:FB]->(:Bar) RETURN f").<Node>columnAs("f").next();
        TestUtil.testCall(db, "CALL apoc.refactor.cloneNodes([$node]) yield output as node return properties(node) as props,[(node)-[r]->() | type(r)] as types",
                map("node",node),
                (row) -> {
                assertEquals(map("name","foo","age",42L),row.get("props"));
                assertEquals(emptyList(),row.get("types"));
                }
        );
        TestUtil.testCall(db, "CALL apoc.refactor.cloneNodes([$node],true,[]) yield output as node return properties(node) as props,[(node)-[r]->() | type(r)] as types",
                map("node",node),
                (row) -> {
                assertEquals(map("name","foo","age",42L),row.get("props"));
                assertEquals(singletonList("FB"),row.get("types"));
                }
        );
        TestUtil.testCall(db, "CALL apoc.refactor.cloneNodes([$node],false,[]) yield output as node return properties(node) as props,[(node)-[r]->() | type(r)] as types",
                map("node",node),
                (row) -> {
                assertEquals(map("name","foo","age",42L),row.get("props"));
                assertEquals(emptyList(),row.get("types"));
                }
        );
        TestUtil.testCall(db, "CALL apoc.refactor.cloneNodes([$node],true,['age']) yield output as node return properties(node) as props,[(node)-[r]->() | type(r)] as types",
                map("node",node),
                (row) -> {
                assertEquals(map("name","foo"),row.get("props"));
                assertEquals(singletonList("FB"),row.get("types"));
                }
        );
    }

    @Test
    public void testMergeNodes() throws Exception {

    }

    @Test
    public void testChangeType() throws Exception {

    }

    @Test
    public void testRedirectRelationship() throws Exception {

    }

    @Test
    public void testMergeNodesWithConstraints() throws Exception {
        db.execute("CREATE CONSTRAINT ON (p:Person) ASSERT p.name IS UNIQUE").close();
        long id = db.execute("CREATE (p1:Person {name:'Foo'}), (p2:Person {surname:'Bar'}) RETURN id(p1) as id ").<Long>columnAs("id").next();
        ExecutionPlanDescription plan = db.execute("EXPLAIN MATCH (o:Person {ID:{oldID}}), (n:Person {ID:{newID}}) CALL apoc.refactor.mergeNodes([o,n]) yield node return node").getExecutionPlanDescription();
        System.out.println(plan);
        System.out.flush();
        testCall(db, "MATCH (o:Person {name:'Foo'}), (n:Person {surname:'Bar'}) CALL apoc.refactor.mergeNodes([o,n]) yield node return node",
                (r) -> {
                    Node node = (Node) r.get("node");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Person")));
                    assertEquals("Foo", node.getProperty("name"));
                    assertEquals("Bar", node.getProperty("surname"));
                });
    }

    @Test
    public void testMergeNodesWithIngoingRelationships() throws Exception {
        long lisaId = Iterators.single(db.execute("CREATE \n" +
                "(alice:Person {name:'Alice'}),\n" +
                "(bob:Person {name:'Bob'}),\n" +
                "(john:Person {name:'John'}),\n" +
                "(lisa:Person {name:'Lisa'}),\n" +
                "(alice)-[:knows]->(bob),\n" +
                "(lisa)-[:knows]->(alice),\n" +
                "(bob)-[:knows]->(john) return id(lisa) as lisaId").columnAs("lisaId"));

        //Merge (Bob) into (Lisa).
        // The updated node should have one ingoing edge from (Alice), and two outgoing edges to (John) and (Alice).
        testCall(db,
                "MATCH (bob:Person {name:'Bob'}), (lisa:Person {name:'Lisa'}) CALL apoc.refactor.mergeNodes([lisa, bob]) yield node return node, bob",
                (r)-> {
                    Node node = (Node) r.get("node");
                    assertEquals(lisaId, node.getId());
                    assertEquals("Bob", node.getProperty("name"));
                    assertEquals(1, node.getDegree(Direction.INCOMING));
                    assertEquals(2, node.getDegree(Direction.OUTGOING));
                    assertEquals("Alice", node.getRelationships(Direction.INCOMING).iterator().next().getStartNode().getProperty("name"));

                });
    }

    @Test
    public void testMergeNodesWithSelfRelationships() throws Exception {
        Map<String, Object> result = Iterators.single(db.execute("CREATE \n" +
                "(alice:Person {name:'Alice'}),\n" +
                "(bob:Person {name:'Bob'}),\n" +
                "(bob)-[:likes]->(bob) RETURN id(alice) AS aliceId, id(bob) AS bobId"));

        // Merge (bob) into (alice).
        // The updated node should have one self relationship.
        // NB: the "LIMIT 1" here is important otherwise Cypher tries to check if another MATCH is found, causing a failing read attempt to deleted node
        testCall(db,
                "MATCH (alice:Person {name:'Alice'}), (bob:Person {name:'Bob'}) WITH * LIMIT 1 CALL apoc.refactor.mergeNodes([alice, bob]) yield node return node",
                (r)-> {
                    Node node = (Node) r.get("node");
                    assertEquals(result.get("aliceId"), node.getId());
                    assertEquals("Bob", node.getProperty("name"));
                    assertEquals(1, node.getDegree(Direction.INCOMING));
                    assertEquals(1, node.getDegree(Direction.OUTGOING));
                    assertTrue(node.getSingleRelationship(RelationshipType.withName("likes"), Direction.OUTGOING).getEndNode().equals(node));
                });
    }

    @Test
    public void testMergeRelsOverwriteEagerAggregation() throws Exception {
        long id = db.execute("Create (d:Person {name:'Daniele'})\n" + "Create (p:Country {name:'USA'})\n" + "Create (d)-[:TRAVELS_TO {year:1995, reason:\"work\"}]->(p)\n"
                + "Create (d)-[:GOES_TO {year:2010}]->(p)\n" + "Create (d)-[:FLIGHTS_TO {company:\"Air America\"}]->(p) RETURN id(p) as id ").<Long>columnAs("id").next();
        testCall(db, "MATCH (d:Person {name:'Daniele'})\n" + "MATCH (p:Country {name:'USA'})\n" + "MATCH (d)-[r:TRAVELS_TO]->(p)\n" + "MATCH (d)-[h:GOES_TO]->(p)\n"
                        + "MATCH (d)-[l:FLIGHTS_TO]->(p)\n" + "call apoc.refactor.mergeRelationships([r,h,l],{properties:\"overwrite\"}) yield rel\n MATCH (d)-[u]->(p) " + "return p,d,u,u.to as to, count(u) as totRel",
                (r) -> {
                    Node node = (Node) r.get("p");
                    Long totRel = (Long) r.get("totRel");
                    Relationship rel = (Relationship) r.get("u");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Country")));
                    assertEquals("USA", node.getProperty("name"));
                    assertEquals(new Long(1),totRel);
                    assertEquals(true, rel.isType(RelationshipType.withName("TRAVELS_TO")));
                    assertEquals("work", rel.getProperty("reason"));
                    assertEquals(2010L, rel.getProperty("year"));
                });
    }

    @Test
    public void testMergeRelsCombineEagerAggregation() throws Exception {
        long id = db.execute("Create (d:Person {name:'Daniele'})\n" + "Create (p:Country {name:'USA'})\n" + "Create (d)-[:TRAVELS_TO {year:1995, reason:\"work\"}]->(p)\n"
                + "Create (d)-[:GOES_TO {year:2010, reason:\"fun\"}]->(p)\n" + "Create (d)-[:FLIGHTS_TO {company:\"Air America\"}]->(p) RETURN id(p) as id ").<Long>columnAs("id").next();
        testCall(db, "MATCH (d:Person {name:'Daniele'})\n" + "MATCH (p:Country {name:'USA'})\n" + "MATCH (d)-[r:TRAVELS_TO]->(p)\n" + "MATCH (d)-[h:GOES_TO]->(p)\n"
                        + "MATCH (d)-[l:FLIGHTS_TO]->(p)\n" + "call apoc.refactor.mergeRelationships([r,h,l],{properties:\"discard\"}) yield rel\n MATCH (d)-[u]->(p) " + "return p,d,u,u.to as to, count(u) as totRel",
                (r) -> {
                    Node node = (Node) r.get("p");
                    Long totRel = (Long) r.get("totRel");
                    Relationship rel = (Relationship) r.get("u");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Country")));
                    assertEquals("USA", node.getProperty("name"));
                    assertEquals(new Long(1),totRel);
                    assertEquals(true, rel.isType(RelationshipType.withName("TRAVELS_TO")));
                    assertEquals("work", rel.getProperty("reason"));
                    assertEquals(1995L, rel.getProperty("year"));
                });
    }

    @Test
    public void testMergeRelsEagerAggregationCombineSingleValuesProperty() throws Exception {
        long id = db.execute("Create (d:Person {name:'Daniele'})\n" + "Create (p:Country {name:'USA'})\n" + "Create (d)-[:TRAVELS_TO {year:1995, reason:\"work\"}]->(p)\n"
                + "Create (d)-[:GOES_TO {year:2010, reason:\"fun\"}]->(p)\n" + "Create (d)-[:FLIGHTS_TO {company:\"Air America\"}]->(p) RETURN id(p) as id ").<Long>columnAs("id").next();
        testCall(db, "MATCH (d:Person {name:'Daniele'})\n" + "MATCH (p:Country {name:'USA'})\n" + "MATCH (d)-[r:TRAVELS_TO]->(p)\n" + "MATCH (d)-[h:GOES_TO]->(p)\n"
                        + "MATCH (d)-[l:FLIGHTS_TO]->(p)\n" + "call apoc.refactor.mergeRelationships([r,h,l],{properties:\"combine\"}) yield rel\n MATCH (d)-[u]->(p) " + "return p,d,u,u.to as to, count(u) as totRel",
                (r) -> {
                    Node node = (Node) r.get("p");
                    Long totRel = (Long) r.get("totRel");
                    Relationship rel = (Relationship) r.get("u");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Country")));
                    assertEquals("USA", node.getProperty("name"));
                    assertEquals(new Long(1),totRel);
                    assertEquals(true, rel.isType(RelationshipType.withName("TRAVELS_TO")));
                    assertEquals(Arrays.asList("work", "fun").toArray(), new ArrayBackedList(rel.getProperty("reason")).toArray());
                    assertEquals(Arrays.asList(1995L, 2010L).toArray(), new ArrayBackedList(rel.getProperty("year")).toArray());
                });
    }

    @Test
    public void testMergeRelsEagerAggregationCombineArrayDifferentValuesTypeProperties() throws Exception {
        long id = db.execute("Create (d:Person {name:'Daniele'})\n" + "Create (p:Country {name:'USA'})\n" + "Create (d)-[:TRAVELS_TO {year:1995, reason:\"work\"}]->(p)\n"
                + "Create (d)-[:GOES_TO {year:[\"2010\",\"2015\"], reason:\"fun\"}]->(p)\n" + "Create (d)-[:FLIGHTS_TO {company:\"Air America\"}]->(p) RETURN id(p) as id ").<Long>columnAs("id").next();
        testCall(db, "MATCH (d:Person {name:'Daniele'})\n" + "MATCH (p:Country {name:'USA'})\n" + "MATCH (d)-[r:TRAVELS_TO]->(p)\n" + "MATCH (d)-[h:GOES_TO]->(p)\n"
                        + "MATCH (d)-[l:FLIGHTS_TO]->(p)\n" + "call apoc.refactor.mergeRelationships([r,h,l],{properties:\"combine\"}) yield rel\n MATCH (d)-[u]->(p) " + "return p,d,u,u.to as to, count(u) as totRel",
                (r) -> {
                    Node node = (Node) r.get("p");
                    Long totRel = (Long) r.get("totRel");
                    Relationship rel = (Relationship) r.get("u");
                    assertEquals(id, node.getId());
                    assertEquals(true, node.hasLabel(Label.label("Country")));
                    assertEquals("USA", node.getProperty("name"));
                    assertEquals(new Long(1),totRel);
                    assertEquals(true, rel.isType(RelationshipType.withName("TRAVELS_TO")));
                    assertEquals(Arrays.asList("work", "fun").toArray(), new ArrayBackedList(rel.getProperty("reason")).toArray());
                    assertEquals(Arrays.asList("1995", "2010", "2015").toArray(), new ArrayBackedList(rel.getProperty("year")).toArray());
                });
    }
}
