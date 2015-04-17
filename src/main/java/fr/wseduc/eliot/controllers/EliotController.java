package fr.wseduc.eliot.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.eliot.pojo.Applications;
import fr.wseduc.rs.Get;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import org.entcore.common.http.response.DefaultPages;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EliotController extends BaseController {

	enum Application { ABSENCES, AGENDA, NOTES, SCOLARITE, TDBASE, TEXTES }

	private Map<String, Applications> allowedApplication;
	private final Map<String, String> roles = new HashMap<>();
	private long exportedDelay;
	private HttpClient client;
	private String appliCode;

	@Override
	public void init(Vertx vertx, Container container, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, container, rm, securedActions);
		exportedDelay = container.config().getLong("exported-delay", 5 * 60 * 1000l);
		appliCode = container.config().getString("appli-code");
		ConcurrentSharedMap<Object, Object> server = vertx.sharedData().getMap("server");
		Boolean cluster = (Boolean) server.get("cluster");
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
		configureApplications(null);
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
					uri.append("/adapter#")
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
						for (fr.wseduc.eliot.pojo.Application app : apps.getApplications()) {
							if (application.name().equals(app.getCode())) {
								handler.handle(apps.getRne());
								return;
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
	public void repositoryEventsHandle(Message<JsonObject> message) {
		String action = message.body().getString("action", "");
		switch (action) {
			case "exported" :
				exported(message);
				break;
			default:
				sendError(message, "invalid.action");
		}
	}

	private void exported(final Message<JsonObject> message) {
		log.info("exported");
		if ("ELIOT".equals(message.body().getString("exportFormat"))) {
			vertx.setTimer(exportedDelay, new Handler<Long>() {
				@Override
				public void handle(Long event) {
					configureApplications(message);
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
					});
				} else if (message != null) {
					sendError(message, "get.structures.error");
				}
			}
		});
	}

	private void sendApplications(Set<String> apps, final Handler<Boolean> handler) {
		final AtomicInteger count = new AtomicInteger(apps.size());
		final AtomicBoolean success = new AtomicBoolean(true);
		for (String app : apps) {
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
						success.set(false);
					}
					if (count.decrementAndGet() <= 0) {
						handler.handle(success.get());
					}
				}
			});
		}
	}

	private void sendRoles(final Set<String> apps, final Handler<Boolean> handler) {
		final AtomicInteger count = new AtomicInteger(apps.size());
		final AtomicBoolean success = new AtomicBoolean(true);
		for (final String application: apps) {
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
						success.set(false);
					}
					if (count.decrementAndGet() <= 0) {
						JsonObject listRolesMessage = new JsonObject()
								.putString("action", "list-roles");
						eb.send("wse.app.registry.bus", listRolesMessage, new Handler<Message<JsonObject>>() {
							@Override
							public void handle(Message<JsonObject> event) {
								JsonArray result = event.body().getArray("result");
								if (!"ok".equals(event.body().getString("status")) || result == null) {
									log.error(event.body().getString("message"));
									success.set(false);
								} else {
									for (Object o : result) {
										if (!(o instanceof JsonObject)) continue;
										JsonObject j = (JsonObject) o;
										if (apps.contains(j.getString("name"))) {
											roles.put(j.getString("name"), j.getString("id"));
										}
									}
								}
								handler.handle(success.get());
							}
						});
					}
				}
			});
		}
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
											j.getString("name", "").contains("-Personnel"))) {
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
