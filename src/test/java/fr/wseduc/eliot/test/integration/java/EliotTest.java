/*
 * Copyright © Région Nord Pas de Calais-Picardie, Département 91, Région Aquitaine-Limousin-Poitou-Charentes, 2016.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with OPEN ENT NG is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of OPEN ENT NG, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

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
