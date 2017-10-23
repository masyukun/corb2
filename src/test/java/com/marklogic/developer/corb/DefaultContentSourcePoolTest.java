package com.marklogic.developer.corb;

import static com.marklogic.developer.corb.TestUtils.clearSystemProperties;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Properties;

import com.marklogic.xcc.Content;
import com.marklogic.xcc.ContentSource;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.ServerConnectionException;
import com.marklogic.xcc.impl.AdhocImpl;
import com.marklogic.xcc.impl.SessionImpl;
import com.marklogic.xcc.impl.SocketPoolProvider;

public class DefaultContentSourcePoolTest {
	@Before
	public void setUp() throws FileNotFoundException {
		clearSystemProperties();
	}

	private void assertHostAndPort(ContentSource cs, String hostname, int port) {
		assertEquals(hostname,cs.getConnectionProvider().getHostName());
		assertEquals(port,cs.getConnectionProvider().getPort());
	}

	@Test
	public void testInitContentSources() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@localhost:8000"});
		assertTrue(csp.available());
		assertNotNull(csp.get());
		assertEquals(csp.getAllContentSources().length,1);
        assertEquals(DefaultContentSourcePool.CONNECTION_POLICY_ROUND_ROBIN, csp.connectionPolicy);
        csp.close();
	}

	@Test(expected = CorbException.class)
	public void testInitInvalidContentSources() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@localhost1:8000"});
		assertFalse(csp.available());
		csp.get();
		csp.close();
	}

    @Test
    public void testGetWillPauseWhenError() throws CorbException {
	    Properties properties = new Properties();
	    properties.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, Integer.toString(1));
        DefaultContentSourcePool csp = new DefaultContentSourcePool();
        csp.init(properties, null, new String[] {"xcc://foo:bar@localhost:8000"});

        ContentSource cs = csp.nextContentSource();
        csp.error(cs);
        long before = System.currentTimeMillis();
        csp.get();
        long after = System.currentTimeMillis();

        assertTrue(csp.getConnectRetryInterval() * 1000 <= after - before );
    }

    @Test(expected = NullPointerException.class)
    public void testInitNullConnectionStrings() throws CorbException{
        DefaultContentSourcePool csp = new DefaultContentSourcePool();
        csp.init(null, null, null);
        csp.close();
    }

	@Test
	public void testInitTwoContentSources() throws CorbException {
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@localhost:8000/dbase","xcc://foo:bar@192.168.0.1:8000/dbase"});
		assertTrue(csp.available());
		assertEquals(2,csp.getAllContentSources().length);
		assertHostAndPort(csp.get(),"localhost",8000);
		assertHostAndPort(csp.get(),"192.168.0.1",8000);
		csp.close();
	}

	@Test
	public void testInitTwoWithOneInvalidContentSource() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@localhost:8000","xcc://foo:bar@localhost1:8000"});
		assertTrue(csp.available());
		assertEquals(1,csp.getAllContentSources().length);
		assertHostAndPort(csp.get(),"localhost",8000);
		assertHostAndPort(csp.get(),"localhost",8000);
		csp.close();
	}

	@Test
    public void testInitConnectionPolicyRandom() {
	    Properties properties = new Properties();
	    properties.setProperty(Options.CONNECTION_POLICY, DefaultContentSourcePool.CONNECTION_POLICY_RANDOM);
        DefaultContentSourcePool csp = new DefaultContentSourcePool();
        csp.init(properties, null, new String[] {"xcc://foo:bar@localhost:8000","xcc://foo:bar@localhost:8010","xcc://foo:bar@localhost:8020"});
        assertEquals(DefaultContentSourcePool.CONNECTION_POLICY_RANDOM, csp.connectionPolicy);
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        csp.close();
    }

    @Test
    public void testInitConnectionPolicyLoad() {
        Properties properties = new Properties();
        properties.setProperty(Options.CONNECTION_POLICY, DefaultContentSourcePool.CONNECTION_POLICY_LOAD);
        DefaultContentSourcePool csp = new DefaultContentSourcePool();
        csp.init(properties, null, new String[] {"xcc://foo:bar@localhost:8000","xcc://foo:bar@localhost:8010","xcc://foo:bar@localhost:8020"});

        assertEquals(DefaultContentSourcePool.CONNECTION_POLICY_LOAD, csp.connectionPolicy);
        assertTrue(csp.isLoadPolicy());
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        csp.close();
    }

    @Test
    public void testInitConnectionPolicyRoundRobin() {
        DefaultContentSourcePool csp = initRoundRobinPool();

        assertEquals(DefaultContentSourcePool.CONNECTION_POLICY_ROUND_ROBIN, csp.connectionPolicy);
        assertEquals(3, csp.getAvailableContentSources().size());
        ContentSource firstContentSource = csp.nextContentSource();
        assertNotNull(firstContentSource);
        assertNotNull(csp.nextContentSource());
        assertNotNull(csp.nextContentSource());
        assertEquals(firstContentSource, csp.nextContentSource());
        csp.remove(firstContentSource);
        assertEquals(2, csp.getAvailableContentSources().size());
        csp.remove(csp.nextContentSource());
        csp.remove(csp.nextContentSource());
        csp.remove(csp.nextContentSource());
        assertNull(csp.nextContentSource());
        csp.close();
    }

    @Test
    public void testError(){
        DefaultContentSourcePool csp = initRoundRobinPool();
        ContentSource cs = csp.nextContentSource();
        assertEquals(0, csp.errorCount(cs));
        csp.error(cs);
        assertEquals(1, csp.errorCount(cs));
        csp.success(cs);
        assertEquals(0, csp.errorCount(cs));
        csp.error(cs);
        csp.error(cs);
        assertEquals(2, csp.errorCount(cs));
        csp.error(cs);
        assertEquals(3, csp.errorCount(cs));
        csp.error(cs);
        assertEquals(0, csp.errorCount(cs));
        csp.close();
    }

    @Test
    public void testGetAvailableContentSources(){
        DefaultContentSourcePool csp = initRoundRobinPool();
        ContentSource cs = csp.nextContentSource();
        assertEquals(3, csp.getAvailableContentSources().size());
        csp.error(cs);
        assertEquals(2, csp.getAvailableContentSources().size());
        csp.close();
    }

    @Test
    public void testHoldAndRelease() {
        DefaultContentSourcePool csp = initRoundRobinPool();
        ContentSource cs = csp.nextContentSource();
        assertNull(csp.connectionCountsMap.get(cs));
        csp.hold(cs);
        assertEquals(1, csp.connectionCountsMap.get(cs).intValue());
        csp.hold(cs);
        assertEquals(2, csp.connectionCountsMap.get(cs).intValue());
        csp.release(cs);
        assertEquals(1, csp.connectionCountsMap.get(cs).intValue());
        csp.release(cs);
        assertEquals(0, csp.connectionCountsMap.get(cs).intValue());
        csp.close();
    }

    private DefaultContentSourcePool initRoundRobinPool() {
        Properties properties = new Properties();
        properties.setProperty(Options.CONNECTION_POLICY, DefaultContentSourcePool.CONNECTION_POLICY_ROUND_ROBIN);
        DefaultContentSourcePool csp = new DefaultContentSourcePool();
        csp.init(properties, null, new String[] {"xcc://foo:bar@localhost:8000","xcc://foo:bar@localhost:8010","xcc://foo:bar@localhost:8020"});
        return csp;
    }

	@Test
	public void testRoundRobinPolicy() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		assertEquals(3,csp.getAllContentSources().length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.close();
	}

	@Test
	public void testRoundRobinPolicyWithOneError() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		assertEquals(3,csp.getAllContentSources().length);
		ContentSource ecs = null;
		assertHostAndPort((ecs=csp.get()),"192.168.0.1",8001);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.error(csp.getContentSourceFromProxy(ecs));
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.close();
	}

	@Test
	public void testRoundRobinPolicyWithTwoErrors() throws CorbException{
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		assertEquals(3,csp.getAllContentSources().length);
		ContentSource ecs1 = null;
		ContentSource ecs2 = null;
		assertHostAndPort((ecs1=csp.get()),"192.168.0.1",8001);
		assertHostAndPort((ecs2=csp.get()),"192.168.0.2",8002);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.error(csp.getContentSourceFromProxy(ecs1));
		csp.error(csp.getContentSourceFromProxy(ecs2));
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.close();
	}

	@Test
	public void testRoundRobinPolicyWithUnexpiredContentSource() throws CorbException, InterruptedException{
		System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		ContentSource ecs1 = null;
		assertHostAndPort((ecs1=csp.get()),"192.168.0.1",8001);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.error(csp.getContentSourceFromProxy(ecs1));
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		Thread.sleep(1000L);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.close();
	}

	@Test
	public void testRoundRobinPolicyWithReactivatedContentSource() throws CorbException, InterruptedException{
		System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		ContentSource ecs1 = null;
		assertHostAndPort((ecs1=csp.get()),"192.168.0.1",8001);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.error(csp.getContentSourceFromProxy(ecs1));
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.success(csp.getContentSourceFromProxy(ecs1));
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.close();
	}

	@Test
	public void testRoundRobinPolicyWithAllErrors() throws CorbException{
	    Properties properties = new Properties();
        properties.setProperty(Options.XCC_CONNECTION_RETRY_LIMIT, "1");
        properties.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "0");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(properties, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		assertEquals(3,csp.getAllContentSources().length);
		ContentSource ecs1 = null;
		ContentSource ecs2 = null;
		ContentSource ecs3 = null;
		assertHostAndPort((ecs1=csp.get()),"192.168.0.1",8001);
		assertHostAndPort((ecs2=csp.get()),"192.168.0.2",8002);
		assertHostAndPort((ecs3=csp.get()),"192.168.0.3",8003);
		csp.error(csp.getContentSourceFromProxy(ecs1));
		csp.error(csp.getContentSourceFromProxy(ecs2));
		csp.error(csp.getContentSourceFromProxy(ecs3));
		csp.get();
		csp.close();
	}

	@Test
	public void tryToTestRandomPolicy() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "RANDOM");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		assertTrue(Arrays.asList(new String[]{"192.168.0.1","192.168.0.2"}).contains(csp.get().getConnectionProvider().getHostName()));
		assertTrue(Arrays.asList(new String[]{"192.168.0.1","192.168.0.2"}).contains(csp.get().getConnectionProvider().getHostName()));
		csp.close();
	}

	@Test
	public void testRandomPolicyWithOneError() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "RANDOM");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		assertTrue(Arrays.asList(new String[]{"192.168.0.1","192.168.0.2"}).contains(csp.get().getConnectionProvider().getHostName()));
		assertTrue(Arrays.asList(new String[]{"192.168.0.1","192.168.0.2"}).contains(csp.get().getConnectionProvider().getHostName()));
		csp.error(csp.getAllContentSources()[0]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.close();
	}

	@Test
	public void testLoadPolicy() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(2,csList.length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		assertHostAndPort(csp.get(),"192.168.0.1",8001); //should get the same host as there is no load
		csp.hold(csList[0]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.release(csList[0]);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.close();
	}

	@Test
	public void testLoadPolicy2() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(2,csList.length);
		csp.hold(csList[0]);
		csp.hold(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.hold(csList[0]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.close();
	}

	@Test
	public void testLoadPolicyWithOneError() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(3,csList.length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.error(csList[0]);
		csp.hold(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.release(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.close();
	}

	@Test
	public void testLoadPolicyWithTwoErrors() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(3,csList.length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.error(csList[0]);
		csp.error(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.hold(csList[2]);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.close();
	}

	@Test
	public void testLoadPolicyWithReactivatedContentSource() throws CorbException{
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002","xcc://foo:bar@192.168.0.3:8003"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(3,csList.length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.hold(csList[0]);
		csp.error(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.3",8003);
		csp.success(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.close();
	}

	@Test(expected = CorbException.class)
	public void testLoadPolicyWithAllErrors() throws CorbException{
		System.setProperty(Options.XCC_CONNECTION_RETRY_LIMIT, "1");
	    System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "0");
		System.setProperty(Options.CONNECTION_POLICY, "LOAD");
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.init(null, null, new String[] {"xcc://foo:bar@192.168.0.1:8001","xcc://foo:bar@192.168.0.2:8002"});
		ContentSource[] csList = csp.getAllContentSources();
		assertEquals(2,csList.length);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.error(csList[0]);
		csp.error(csList[1]);
		assertHostAndPort(csp.get(),"192.168.0.1",8001);
		csp.error(csList[0]);
		assertHostAndPort(csp.get(),"192.168.0.2",8002);
		csp.error(csList[1]);
		csp.get();
		csp.close();
	}

	@Test
	public void testSubmitWithMockRequest() throws RequestException, CorbException {
		ContentSource cs = mock(ContentSource.class);
		Session session = mock(Session.class);
		AdhocImpl request = mock(AdhocImpl.class);
		ResultSequence rs = mock(ResultSequence.class);
		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.contentSourceList.add(cs);
		when(cs.newSession()).thenReturn(session);
		when(session.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(request.getSession()).thenReturn(session);
		when(session.submitRequest(request)).thenReturn(rs);

		csp.get().newSession().submitRequest(request);
		csp.close();
	}

	@Test
	public void testSubmitWithMockRequestWithFailOver() throws RequestException, CorbException {
		System.setProperty(Options.XCC_CONNECTION_RETRY_LIMIT, "1");
	    System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		ContentSource cs1 = mock(ContentSource.class);
		Session session1 = mock(SessionImpl.class);

		ContentSource cs2 = mock(ContentSource.class);
		Session session2 = mock(SessionImpl.class);

		AdhocImpl request = mock(AdhocImpl.class);
		ResultSequence result = mock(ResultSequence.class);

		when(cs1.newSession()).thenReturn(session1);
		when(cs1.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost1",8001));

		when(cs2.newSession()).thenReturn(session2);
		when(cs2.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost2",8002));

		when(session1.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(session1.submitRequest(Mockito.any())).thenThrow(mock(ServerConnectionException.class));

		when(session2.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(session2.submitRequest(Mockito.any())).thenReturn(result);

		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.contentSourceList.add(cs1);
		csp.contentSourceList.add(cs2);

		ResultSequence gotResult = csp.get().newSession().submitRequest(request);
		assertTrue(csp.errorCountsMap.get(cs1) == 1);
		assertEquals(result,gotResult);
		csp.close();
	}

	@Test
	public void testSubmitWithMockInsertWithFailOver() throws RequestException, CorbException {
		System.setProperty(Options.XCC_CONNECTION_RETRY_LIMIT, "1");
	    System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		ContentSource cs1 = mock(ContentSource.class);
		Session session1 = mock(SessionImpl.class);

		ContentSource cs2 = mock(ContentSource.class);
		Session session2 = mock(SessionImpl.class);

		Content content = mock(Content.class);

		when(cs1.newSession()).thenReturn(session1);
		when(cs1.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost1",8001));

		when(cs2.newSession()).thenReturn(session2);
		when(cs2.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost2",8002));

		doThrow(mock(ServerConnectionException.class)).when(session1).insertContent(content);
		doNothing().when(session2).insertContent(content);

		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.contentSourceList.add(cs1);
		csp.contentSourceList.add(cs2);

		csp.get().newSession().insertContent(content);
		assertTrue(csp.errorCountsMap.get(cs1) == 1);
		csp.close();
	}

	@Test
	public void testSubmitWithMockRequestAndErrorAndWait() throws RequestException, CorbException {
		System.setProperty(Options.XCC_CONNECTION_RETRY_LIMIT, "1");
	    System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		ContentSource cs = mock(ContentSource.class);
		Session session = mock(SessionImpl.class);

		AdhocImpl request = mock(AdhocImpl.class);
		ResultSequence result = mock(ResultSequence.class);

		when(cs.newSession()).thenReturn(session);
		when(cs.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost1",8001));

		when(session.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(session.submitRequest(Mockito.any())).thenReturn(result);

		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.contentSourceList.add(cs);
		csp.error(cs);

		ResultSequence gotResult = csp.get().newSession().submitRequest(request);
		assertEquals(result,gotResult);

		csp.close();
	}

	@Test(expected = ServerConnectionException.class)
	public void testSubmitWithMockRequestAndError() throws RequestException, CorbException {
		System.setProperty(Options.XCC_CONNECTION_RETRY_INTERVAL, "1");
		ContentSource cs1 = mock(ContentSource.class);
		Session session1 = mock(SessionImpl.class);

		ContentSource cs2 = mock(ContentSource.class);
		Session session2 = mock(SessionImpl.class);

		AdhocImpl request = mock(AdhocImpl.class);

		when(cs1.newSession()).thenReturn(session1);
		when(cs1.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost1",8001));

		when(cs2.newSession()).thenReturn(session2);
		when(cs2.getConnectionProvider()).thenReturn(new SocketPoolProvider("localhost2",8002));

		when(session1.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(session1.submitRequest(Mockito.any())).thenThrow(mock(ServerConnectionException.class));

		when(session2.newAdhocQuery(Mockito.any())).thenReturn(request);
		when(session2.submitRequest(Mockito.any())).thenThrow(mock(ServerConnectionException.class));

		DefaultContentSourcePool csp = new DefaultContentSourcePool();
		csp.contentSourceList.add(cs1);
		csp.contentSourceList.add(cs2);

		csp.get().newSession().submitRequest(request);
		csp.close();
	}

}