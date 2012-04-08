package codegraph;

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.ReadableIndex;
import org.neo4j.kernel.Config;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Load a jar into the graph database.
 * <p/>
 * User: sam
 * Date: 4/8/12
 * Time: 12:37 PM
 */
public class Loader {

  @Argument(alias = "r", description = "reset the database before indexing")
  private static Boolean reset = false;

  @Argument(alias = "a", description = "artifact id for the jar")
  private static String artifactId;

  @Argument(alias = "g", description = "group id for the jar")
  private static String groupId;

  @Argument(alias = "v", description = "version for the jar")
  private static String version;

  @Argument(alias = "f", description = "filename of the jar", required = true)
  private static File file;

  @Argument(alias = "db", description = "location of the graph database")
  private static String database = "codegraph-db";

  @Argument(alias = "p", description = "package filters", delimiter = ",")
  private static String[] packages = new String[]{"sun", "com.sun"};

  public static void main(String[] args) throws IOException {
    try {
      Args.parse(Loader.class, args);
    } catch (IllegalArgumentException e) {
      Args.usage(Loader.class);
      System.exit(1);
    }

    JarFile jarFile;
    if (!file.exists()) {
      System.err.println("Could not find jar: " + file);
      System.exit(1);
      return;
    } else {
      jarFile = new JarFile(file);
    }

    Map<String, String> config = new HashMap<>();
    config.put(Config.NODE_AUTO_INDEXING, "true");
    config.put(Config.RELATIONSHIP_AUTO_INDEXING, "true");
    config.put(Config.NODE_KEYS_INDEXABLE, "artifactId, groupId, version, name, class, desc, type");
    config.put(Config.RELATIONSHIP_KEYS_INDEXABLE, "CALLS, EXTENDS, CONTAINS");

    final EmbeddedGraphDatabase gdb = new EmbeddedGraphDatabase(database, config);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        gdb.shutdown();
      }
    });


    if (reset) {
      Transaction transaction = gdb.beginTx();
      for (Node node : gdb.getAllNodes()) {
        node.delete();
      }
      transaction.success();
      transaction.finish();
    }

    final ReadableIndex<Node> index = gdb.index().getNodeAutoIndexer().getAutoIndex();
    Transaction tx = gdb.beginTx();
    try {
      BooleanQuery bq = new BooleanQuery();
      bq.add(q("groupId", groupId));
      bq.add(q("artifactId", artifactId));
      bq.add(q("version", version));

      final IndexHits<Node> query = index.query(bq);
      for (Node node : query) {
        node.delete();
      }

      final Node jar = gdb.createNode();
      jar.setProperty("name", file.getName());
      jar.setProperty("groupId", groupId);
      jar.setProperty("artifactId", artifactId);
      jar.setProperty("version", version);
      tx.success();

      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry jarEntry = entries.nextElement();
        if (jarEntry.getName().endsWith(".class")) {
          final ClassReader cr = new ClassReader(jarFile.getInputStream(jarEntry));
          cr.accept(new ClassVisitor(Opcodes.ASM4) {
            Node classNode;

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
              Transaction transaction = gdb.beginTx();
              final Node methodNode = findOrCreateMethod(name, desc, cr.getClassName());
              classNode.createRelationshipTo(methodNode, Relationships.CONTAINS);
              transaction.success();
              return new MethodVisitor(Opcodes.ASM4) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                  Transaction transaction = gdb.beginTx();
                  Node callee = findOrCreateMethod(name, desc, owner);
                  methodNode.createRelationshipTo(callee, Relationships.CALLS);
                  transaction.success();
                }
              };
            }

            private Node findOrCreateMethod(String name, String desc, String className) {
              BooleanQuery bq = new BooleanQuery();
              bq.add(q("name", name));
              bq.add(q("class", className));
              bq.add(q("desc", desc));
              bq.add(q("version", version));
              bq.add(q("type", "method"));
              Node methodNode = index.query(bq).getSingle();
              if (methodNode == null) {
                methodNode = gdb.createNode();
                methodNode.setProperty("name", name);
                methodNode.setProperty("class", className);
                methodNode.setProperty("desc", desc);
                methodNode.setProperty("version", version);
                methodNode.setProperty("type", "method");
              }
              return methodNode;
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
              // Get them on the outer call
            }

            @Override
            public void visitOuterClass(String owner, String name, String desc) {
              Transaction transaction = gdb.beginTx();
              Node outerClass = findOrCreateClass(name);
              classNode.createRelationshipTo(outerClass, Relationships.CONTAINS);
              transaction.success();
            }

            private Node findOrCreateClass(String name) {
              System.out.println(name);
              BooleanQuery bq = new BooleanQuery();
              bq.add(q("name", name));
              bq.add(q("version", version));
              bq.add(q("type", "class"));
              Node outerClass = index.query(bq).getSingle();
              if (outerClass == null) {
                outerClass = gdb.createNode();
                outerClass.setProperty("name", name);
                outerClass.setProperty("version", version);
                outerClass.setProperty("type", "class");
              }
              return outerClass;
            }

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
              Transaction transaction = gdb.beginTx();
              classNode = findOrCreateClass(name);
              Node superClass = findOrCreateClass(superName);
              classNode.createRelationshipTo(superClass, Relationships.EXTENDS);
              for (String anInterface : interfaces) {
                Node interfaceNode = findOrCreateClass(anInterface);
                classNode.createRelationshipTo(interfaceNode, Relationships.IMPLEMENTS);
              }
              jar.createRelationshipTo(classNode, Relationships.CONTAINS);
              transaction.success();
            }
          }, 0);
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
      tx.failure();
    } finally {
      tx.finish();
    }
  }

  private static BooleanClause q(String key, String value) {
    return new BooleanClause(new TermQuery(new Term(key, value)), BooleanClause.Occur.MUST);
  }

}
