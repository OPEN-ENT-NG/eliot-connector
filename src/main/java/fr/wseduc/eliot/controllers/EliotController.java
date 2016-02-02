package fr.wseduc.eliot.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.eliot.pojo.Applications;
import fr.wseduc.rs.Get;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import org.entcore.common.http.response.DefaultPages;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.StatementsBuilder;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.*;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.shareddata.ConcurrentSharedMap;
import org.vertx.java.core.spi.cluster.ClusterManager;
import org.vertx.java.platform.Container;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EliotController extends BaseController {

	private Neo4j neo4j = Neo4j.getInstance();

	enum Application { ABSENCES, AGENDA, NOTES, SCOLARITE, TDBASE, TEXTES }

	private Map<String, Applications> allowedApplication;
	private final Map<String, String> roles = new HashMap<>();
	private long exportedDelay;
	private HttpClient client;
	private String appliCode;

	public static final String SCOLARITE_EXTERNAL_ID = "SCOLARITE";
	public static final JsonObject SCOLARITE = new JsonObject()
			.putString("externalId", SCOLARITE_EXTERNAL_ID)
			.putString("name", "SCOLARITE");

	@Override
	public void init(Vertx vertx, Container container, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, container, rm, securedActions);
		exportedDelay = container.config().getLong("exported-delay", 5 * 60 * 1000l);
		appliCode = container.config().getString("appli-code");
		ConcurrentSharedMap<Object, Object> server = vertx.sharedData().getMap("server");
		Boolean cluster = (Boolean) server.get("cluster");
		String node = (String) server.get("node");
		if (Boolean.TRUE.equals(cluster) && container.config().getBoolean("cluster", false)) {
			ClusterManager cm = ((VertxInternal) vertx).clusterManager();
			allowedApplication = cm.getSyncMap("eliot");
		} else {
			allowedApplication = new HashMap<>();
		}
		try {
			URI uri = new URI(container.config().getString("uri"));
			client = vertx.createHttpClient()
					.setHost(uri.getHost())
					.setPort(uri.getPort())
					.setMaxPoolSize(16)
					.setSSL("https".equals(uri.getScheme()))
					.setKeepAlive(false);
		} catch (URISyntaxException e) {
			log.error(e.getMessage(), e);
		}
		if (Boolean.TRUE.equals(cluster) && node != null && !node.trim().isEmpty()) {
			try {
				if (Integer.parseInt(node.replaceAll("[A-Za-z]+", "")) % 2 == 0) {
					vertx.setTimer(120000, new Handler<Long>() {
						@Override
						public void handle(Long aLong) {
							configureApplications(null);
						}
					});
				} else {
					configureApplications(null);
				}
			} catch (NumberFormatException e) {
				configureApplications(null);
			}
		} else {
			configureApplications(null);
		}
	}

	@Get("/absences")
	@SecuredAction("eliot.absences")
	public void absences(final HttpServerRequest request) {
		buildURI(request, Application.ABSENCES);
	}

	@Get("/agenda")
	@SecuredAction("eliot.agenda")
	public void agenda(final HttpServerRequest request) {
		buildURI(request, Application.AGENDA);
	}

	@Get("/notes")
	@SecuredAction("eliot.notes")
	public void notes(final HttpServerRequest request) {
		buildURI(request, Application.NOTES);
	}

	@Get("/scolarite")
	@SecuredAction("eliot.scolarite")
	public void scolarite(final HttpServerRequest request) {
		buildURI(request, Application.SCOLARITE);
	}

	@Get("/tdbase")
	@SecuredAction("eliot.tdbase")
	public void tdbase(final HttpServerRequest request) {
		buildURI(request, Application.TDBASE);
	}

	@Get("/textes")
	@SecuredAction("eliot.textes")
	public void textes(final HttpServerRequest request) {
		buildURI(request, Application.TEXTES);
	}

	private void buildURI(final HttpServerRequest request, final Application application) {
		getRne(request, application, new Handler<String>() {

			@Override
			public void handle(String rne) {
				if (rne != null) {
					final StringBuilder uri = new StringBuilder();
					final String host = getScheme(request) + "://" + getHost(request);
					uri.append("/adapter?eliot="+application.name().toLowerCase()+"#")
							.append(host)
							.append("/cas/login?ticketAttributeName=casTicket&service=");
					final StringBuilder eliotUri = new StringBuilder();
					try {
						eliotUri.append(container.config().getString("eliotUri"))
								.append("&rne=").append(rne)
								.append("&module=").append(application.name())
								.append("&hostCAS=").append(URLEncoder.encode(host + "/cas", "UTF-8"));
						uri.append(URLEncoder.encode(eliotUri.toString(), "UTF-8"));
						redirect(request, uri.toString());
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage(), e);
						deny(request);
					}
				} else {
					log.error("Rne is null.");
					deny(request);
				}
			}
		});
	}

	private void getRne(final HttpServerRequest request, final Application application,
			final Handler<String> handler) {
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
			@Override
			public void handle(UserInfos user) {
				if (user != null) {
					for (String structure : user.getStructures()) {
						Applications apps = allowedApplication.get(structure);
						if (apps != null && apps.getRne() != null) {
							for (fr.wseduc.eliot.pojo.Application app : apps.getApplications()) {
								if (application.name().equals(app.getCode())) {
									handler.handle(apps.getRne());
									return;
								}
							}
						}
					}
				}
				deny(request);
			}
		});
	}

	private void deny(HttpServerRequest request) {
		request.response().setStatusCode(401).setStatusMessage("Unauthorized")
				.putHeader("content-type", "text/html").end(DefaultPages.UNAUTHORIZED.getPage());
	}

	@BusAddress("user.repository")
	public void repositoryEventsHandle(final Message<JsonObject> message) {
		String action = message.body().getString("action", "");
		switch (action) {
			case "exported" :
				setScolariteGroups(new VoidHandler() {
					@Override
					protected void handle() {
						exported(message);
					}
				});
				break;
			default:
				sendError(message, "invalid.action");
		}
	}

	private void setScolariteGroups(final VoidHandler handler) {
		String query =
				"MATCH (p:Profile {name:'Personnel'})<-[:COMPOSE]-(f:Function {externalId : {functionEID}}) " +
				"RETURN count(*) > 0 as exists ";
		JsonObject params = new JsonObject().putString("functionEID", SCOLARITE_EXTERNAL_ID);
		neo4j.execute(query, params, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				JsonArray res = message.body().getArray("result");
				if ("ok".equals(message.body().getString("status")) && res != null && res.size() == 1 &&
						res.<JsonObject>get(0).getBoolean("exists", false)) {
					addScolariteFunction(handler);
				} else {
					String query =
							"MATCH (p:Profile { name : 'Personnel'}) " +
							"CREATE p<-[:COMPOSE]-(f:Function {props})";
					JsonObject params = new JsonObject().putObject("props", SCOLARITE);
					neo4j.execute(query, params, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> message) {
							if ("ok".equals(message.body().getString("status"))) {
								addScolariteFunction(handler);
							} else {
								handler.handle(null);
							}
						}
					});
				}
			}

			private void addScolariteFunction(final VoidHandler handler) {
				String query =
						"MATCH (u:User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
						"WHERE LENGTH(FILTER(gId IN u.functions WHERE gId =~ '.*\\\\$(EDU|DIR)\\\\$.*')) > 0 " +
						"RETURN s.id as structureId, COLLECT(u.id) as users " +
						"UNION " +
						"MATCH (f:Function {externalId: 'ADMIN_LOCAL'})<-[:CONTAINS_FUNCTION*0..1]-()" +
						"<-[rf:HAS_FUNCTION]-(u:User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
						"RETURN s.id as structureId, COLLECT(u.id) as users ";
				neo4j.execute(query, (JsonObject) null, new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> message) {
						JsonArray res = message.body().getArray("result");
						if ("ok".equals(message.body().getString("status")) && res != null) {
							StatementsBuilder statements = new StatementsBuilder();
							for (Object o: res) {
								if (!(o instanceof JsonObject)) continue;
								JsonObject j = (JsonObject) o;
								addFunction(statements, j.getString("structureId"), j.getArray("users"));
							}
							removeFunctionForOldAdml(statements);
							neo4j.executeTransaction(statements.build(), null, true, new Handler<Message<JsonObject>>() {
								@Override
								public void handle(Message<JsonObject> message) {
									handler.handle(null);
								}
							});
						} else {
							handler.handle(null);
						}
					}
				});
			}

			private void removeFunctionForOldAdml(StatementsBuilder statements) {
				String query =
						"MATCH (f:Function {externalId:'SCOLARITE'})<-[r:HAS_FUNCTION]-(u:User)" +
						"-[r2:IN]->(fg:FunctionGroup), (f2:Function {externalId: 'ADMIN_LOCAL'}) " +
						"WHERE fg.externalId =~ '.*-SCOLARITE$' " +
						"AND LENGTH(FILTER(gId IN u.functions WHERE gId =~ '.*\\\\$(EDU|DIR)\\\\$.*')) = 0 " +
						"AND NOT(u-[:HAS_FUNCTION]->f2) " +
						"DELETE r, r2";
				statements.add(query);
			}

			private void addFunction(StatementsBuilder statements, String structureId, JsonArray users) {
				String query =
						"MATCH (u:User), (f:Function {externalId:'SCOLARITE'}) " +
						"WHERE u.id IN {users} " +
						"MERGE u-[rf:HAS_FUNCTION]->f " +
						"SET rf.scope = CASE WHEN {scope} IN coalesce(rf.scope, []) THEN " +
						"rf.scope ELSE coalesce(rf.scope, []) + {scope} END";

				JsonObject params = new JsonObject().putArray("users", users).putString("scope", structureId);

				statements.add(query, params);
				String q2 =
						"MATCH (n:Structure {id: {scopeId}}), (f:Function {externalId : 'SCOLARITE'}) " +
						"WITH n, f " +
						"MERGE (fg:Group:FunctionGroup { externalId : {externalId}}) " +
						"ON CREATE SET fg.id = id(fg) + '-' + timestamp(), fg.name = n.name + '-' + f.name " +
						"CREATE UNIQUE n<-[:DEPENDS]-fg";
				String qu =
						"MATCH (u:User), (fg:FunctionGroup { externalId : {externalId}}) " +
						"WHERE u.id IN {users} " +
						"CREATE UNIQUE fg<-[:IN]-u ";
				String extId = structureId + "-SCOLARITE" ;
				JsonObject p2 = new JsonObject()
						.putString("scopeId", structureId)
						.putString("functionCode", "SCOLARITE")
						.putString("externalId", extId);
				statements.add(q2, p2);
				JsonObject pu = new JsonObject()
						.putArray("users", users)
						.putString("externalId", extId);
				statements.add(qu, pu);
			}
		});
	}

	private void exported(final Message<JsonObject> message) {
		log.info("exported");
		if ("ELIOT".equals(message.body().getString("exportFormat"))) {
			vertx.setTimer(exportedDelay, new Handler<Long>() {
				@Override
				public void handle(Long event) {
					if (container.config().getBoolean("sunday-only", true)) {
						Calendar c = Calendar.getInstance();
						c.setTime(new Date());
						int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
						if (Calendar.SUNDAY == dayOfWeek) {
							configureApplications(message);
						}
					} else {
						configureApplications(message);
					}
				}
			});
		} else {
			sendOK(message);
		}
	}

	private void configureApplications(final Message<JsonObject> message) {
		log.info("configure apps");
		getStructures(new Handler<JsonArray>() {
			@Override
			public void handle(final JsonArray structures) {
				if (structures != null) {
					getApplications(structures, new Handler<Map<String, Applications>>() {

						@Override
						public void handle(Map<String, Applications> event) {
							allowedApplication.clear();
							allowedApplication.putAll(event);
							final Set<String> apps = new HashSet<>();
							for (Applications applications : allowedApplication.values()) {
								for (fr.wseduc.eliot.pojo.Application app : applications.getApplications()) {
									apps.add(app.getCode());
								}
							}
							if (message != null) {
								sendApplications(apps, new Handler<Boolean>() {
									@Override
									public void handle(Boolean success) {
										if (success) {
											sendRoles(apps, new Handler<Boolean>() {
												@Override
												public void handle(Boolean success) {
													if (success) {
														linkRolesToGroups(new Handler<Boolean>() {
															@Override
															public void handle(Boolean event) {

															}
														});
													}
												}
											});
										}
									}
								});
							}
						}
					});
				} else if (message != null) {
					sendError(message, "get.structures.error");
				}
			}
		});
	}

	private void sendApplications(Set<String> apps, final Handler<Boolean> handler) {
		int i = apps.size();
		if (i < 1) {
			log.info("Empty applications.");
			return;
		}
		final VoidHandler[] handlers = new VoidHandler[i + 1];
		handlers[i] = new VoidHandler() {
			@Override
			protected void handle() {
				handler.handle(true);
			}
		};
		for (final String app : apps) {
			final int j = --i;
			handlers[j] = new VoidHandler() {
				@Override
				protected void handle() {
					JsonObject application = new JsonObject()
							.putString("name", app)
							.putString("displayName", app.toLowerCase())
							.putString("address", "/eliot/" + app.toLowerCase());
					JsonObject message = new JsonObject()
							.putObject("application", application)
							.putString("action", "create-external-application");
					eb.send("wse.app.registry.bus", message, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error(event.body().getString("message"));
								handler.handle(false);
							} else {
								handlers[j + 1].handle(null);
							}
						}
					});
				}
			};
		}
		handlers[0].handle(null);
	}

	private void sendRoles(final Set<String> apps, final Handler<Boolean> handler) {
		int i = apps.size();
		if (i < 1) {
			log.info("Empty roles.");
			return;
		}
		final VoidHandler[] handlers = new VoidHandler[i + 1];
		handlers[i] = new VoidHandler() {
			@Override
			protected void handle() {
				JsonObject listRolesMessage = new JsonObject()
						.putString("action", "list-roles");
				eb.send("wse.app.registry.bus", listRolesMessage, new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> event) {
						JsonArray result = event.body().getArray("result");
						if (!"ok".equals(event.body().getString("status")) || result == null) {
							log.error(event.body().getString("message"));
							handler.handle(false);
						} else {
							for (Object o : result) {
								if (!(o instanceof JsonObject)) continue;
								JsonObject j = (JsonObject) o;
								if (apps.contains(j.getString("name"))) {
									roles.put(j.getString("name"), j.getString("id"));
								}
							}
							handler.handle(true);
						}
					}
				});
			}
		};
		for (final String application: apps) {
			final int j = --i;
			handlers[j] = new VoidHandler() {
				@Override
				protected void handle() {
					JsonObject role = new JsonObject()
							.putString("name", application);
					JsonArray actions = new JsonArray()
							.add(application + "|address")
							.add(this.getClass().getName() + "|" + application.toLowerCase());
					final JsonObject message = new JsonObject()
							.putString("action", "create-role")
							.putObject("role", role)
							.putArray("actions", actions);
					eb.send("wse.app.registry.bus", message, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error(event.body().getString("message"));
								handler.handle(false);
							} else {
								handlers[j + 1].handle(null);
							}
						}
					});
				}
			};
		}
		handlers[0].handle(null);
	}

	private void linkRolesToGroups(final Handler<Boolean> handler) {
		int i = allowedApplication.size();
		if (i < 1) {
			log.info("Empty allowed application.");
			return;
		}
		final VoidHandler[] handlers = new VoidHandler[i + 1];
		handlers[i] = new VoidHandler() {
			@Override
			protected void handle() {
				handler.handle(true);
			}
		};
		for (final Map.Entry<String, Applications> entry : allowedApplication.entrySet()) {
			final int j = --i;
			handlers[j] = new VoidHandler() {
				@Override
				protected void handle() {
					JsonObject message = new JsonObject()
							.putString("action", "list-groups-with-roles")
							.putString("structureId", entry.getKey());
					eb.send("wse.app.registry.bus", message, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error(event.body().getString("message"));
								handler.handle(false);
							} else {
								linkRolesToGroup(event, entry, new Handler<Boolean>() {
									@Override
									public void handle(Boolean event) {
										if (event) {
											handlers[j + 1].handle(null);
										} else {
											handler.handle(event);
										}
									}
								});
							}
						}
					});
				}
			};
		}
		handlers[0].handle(null);
	}

	private void linkRolesToGroup(Message<JsonObject> event,
			final Map.Entry<String, Applications> entry, final Handler<Boolean> handler) {
		JsonArray result = event.body().getArray("result");
		int k = result.size();
		final VoidHandler[] handlers = new VoidHandler[k + 1];
		handlers[k] = new VoidHandler() {
			@Override
			protected void handle() {
				handler.handle(true);
			}
		};
		for (final Object o : result) {
			final int l = --k;
			handlers[l] = new VoidHandler() {
				@Override
				protected void handle() {
					if (o instanceof JsonObject) {
						JsonObject j = (JsonObject) o;
						JsonObject message = new JsonObject()
								.putString("action", "link-role-group")
								.putString("groupId", j.getString("id"));
						List r = j.getArray("roles").toList();
						r.removeAll(roles.values());
						JsonArray roleIds = new JsonArray(r);
						for (fr.wseduc.eliot.pojo.Application app : entry.getValue().getApplications()) {
							if (!Application.SCOLARITE.name().equals(app.getCode()) ||
									(Application.SCOLARITE.name().equals(app.getCode()) &&
											j.getString("name", "").contains("-SCOLARITE"))) {
								roleIds.add(roles.get(app.getCode()));
							}
						}
						message.putArray("roleIds", roleIds);
						log.debug(message.encodePrettily());
						eb.send("wse.app.registry.bus", message, new Handler<Message<JsonObject>>() {
							@Override
							public void handle(Message<JsonObject> event) {
								if ("ok".equals(event.body().getString("status"))) {
									handlers[l + 1].handle(null);
								} else {
									log.error(event.body().getString("message"));
									handler.handle(false);
								}
							}
						});
					}
				}
			};
		}
		handlers[0].handle(null);
	}

	private void getApplications(JsonArray structures, final Handler<Map<String, Applications>> handler) {
		final Map<String, Applications> appsByStructure = new HashMap<>();
		final AtomicInteger count = new AtomicInteger(structures.size());
		final String baseUri = "/eliot-saas-util/action/webService/getProductEtabWS?appli=" + appliCode + "&rne=";
		for (Object s : structures) {
			if (!(s instanceof JsonObject)) continue;
			final String structure = ((JsonObject) s).getString("id");
			final String rne = ((JsonObject) s).getString("UAI");
			log.info(container.config().getString("uri"));
			log.info(baseUri + rne);
			HttpClientRequest req = client.get(baseUri + rne, new Handler<HttpClientResponse>() {
				@Override
				public void handle(HttpClientResponse event) {
					if (event.statusCode() == 200) {
						event.bodyHandler(new Handler<Buffer>() {
							@Override
							public void handle(Buffer event) {
								try {
									JAXBContext context = JAXBContext.newInstance(Applications.class);
									Unmarshaller um = context.createUnmarshaller();
									Applications applications = (Applications) um.unmarshal(new StringReader(event.toString()));
									applications.setRne(rne);
									appsByStructure.put(structure, applications);
									if (log.isDebugEnabled()) {
										log.debug("Structure : " + structure + " - UAI : " + rne +
												" - APPS : " +event.toString());
									}
								} catch (JAXBException e) {
									log.error("Error when unmarshal applications to structure " + rne, e);
								} finally {
									if (count.decrementAndGet() <= 0) {
										handler.handle(appsByStructure);
									}
								}
							}
						});
					} else {
						log.error("Error " + event.statusCode() + " getting applications to structure " + rne);
						log.error("Status : " + event.statusMessage());
						event.bodyHandler(new Handler<Buffer>() {
							@Override
							public void handle(Buffer event) {
								log.error(">>> " + event.toString());
							}
						});
						if (count.decrementAndGet() <= 0) {
							handler.handle(appsByStructure);
						}
					}
				}
			});
			req.end();
		}
	}

	private void getStructures(final Handler<JsonArray> structures) {
		JsonObject jo = new JsonObject();
		jo.putString("action", "list-structures");
		jo.putArray("fields", new JsonArray().add("id").add("UAI"));
		eb.send("directory", jo, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray result = event.body().getArray("result");
				if ("ok".equals(event.body().getString("status")) && result != null && result.size() > 0) {
					structures.handle(result);
				} else {
					structures.handle(null);
				}
			}
		});
	}

	private void sendError(Message<JsonObject> message, String s) {
		log.error(s);
		message.reply(new JsonObject()
				.putString("status", "error")
				.putString("message", s)
		);
	}

	private void sendOK(Message<JsonObject> message) {
		message.reply(new JsonObject().putString("status", "ok"));
	}

}
