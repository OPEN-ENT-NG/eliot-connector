package fr.wseduc.eliot;

import fr.wseduc.eliot.controllers.EliotController;
import org.entcore.common.http.BaseServer;

public class Eliot extends BaseServer {

	@Override
	public void start() {
		super.start();
		addController(new EliotController());
	}

}
