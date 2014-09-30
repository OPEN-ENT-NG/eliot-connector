package fr.wseduc.eliot.test.integration.java;

import fr.wseduc.eliot.pojo.Applications;
import org.junit.Test;
import org.vertx.testtools.TestVerticle;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import java.io.StringReader;

import static org.vertx.testtools.VertxAssert.*;

public class EliotTest extends TestVerticle {

	@Test
	public void testUnmarshal() throws JAXBException {
		String response = "<LIST><APPLI code='AGENDA'/><APPLI code='TEXTES'/>" +
				"<APPLI code='SCOLARITE'/><APPLI code='TDBASE'/></LIST>";
		JAXBContext context = JAXBContext.newInstance(Applications.class);
		Unmarshaller um = context.createUnmarshaller();
		Applications applications = (Applications) um.unmarshal(new StringReader(response));
		assertEquals(4, applications.getApplications().size());
		assertEquals("AGENDA", applications.getApplications().get(0).getCode());
		assertEquals("TEXTES", applications.getApplications().get(1).getCode());
		assertEquals("SCOLARITE", applications.getApplications().get(2).getCode());
		assertEquals("TDBASE", applications.getApplications().get(3).getCode());
		testComplete();
	}

}
