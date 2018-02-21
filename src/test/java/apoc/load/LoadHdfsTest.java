package apoc.load;

import apoc.util.HdfsTestUtils;
import apoc.util.TestUtil;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

import static apoc.load.LoadCsvTest.assertRow;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testResult;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LoadHdfsTest {

    private GraphDatabaseService db;
    private MiniDFSCluster miniDFSCluster;
    
    @Before public void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder().setConfig("apoc.import.file.enabled","true").newGraphDatabase();
        TestUtil.registerProcedure(db, LoadCsv.class);
        miniDFSCluster = HdfsTestUtils.getLocalHDFSCluster();
		FileSystem fs = miniDFSCluster.getFileSystem();
		String fileName = "test.csv";
		Path file = new Path(fileName);
		try (OutputStream out = fs.create(file);) {
			URL url = ClassLoader.getSystemResource(fileName);
			try (InputStream in = url.openStream();) {
				IOUtils.copy(in, out);
			}
		}
    }

    @After public void tearDown() {
        db.shutdown();
        miniDFSCluster.shutdown();
    }

    @Test public void testLoadCsvFromHDFS() throws Exception {
        testResult(db, "CALL apoc.load.csv({url},{results:['map','list','stringMap','strings']})", map("url", String.format("hdfs://localhost:12345/user/%s/%s",
        		System.getProperty("user.name"), "test.csv")), // 'hdfs://localhost:12345/user/<sys_user_name>/test.csv'
                (r) -> {
                    assertRow(r,0L,"name","Selma","age","8");
                    assertRow(r,1L,"name","Rana","age","11");
                    assertRow(r,2L,"name","Selina","age","18");
                    assertEquals(false, r.hasNext());
                });
    }
}
