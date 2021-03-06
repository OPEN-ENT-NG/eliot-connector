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

package fr.wseduc.eliot.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.cron.CronTrigger;
import fr.wseduc.eliot.pojo.Applications;
import fr.wseduc.rs.Get;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.CookieHelper;
import io.vertx.core.shareddata.LocalMap;
import org.entcore.common.http.response.DefaultPages;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.StatementsBuilder;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import org.vertx.java.core.http.RouteMatcher;


import javax.crypto.Cipher;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static fr.wseduc.webutils.Utils.handlerToAsyncHandler;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static org.entcore.common.user.SessionAttributes.THEME_ATTRIBUTE;

public class EliotController extends BaseController {

	private Neo4j neo4j = Neo4j.getInstance();

	enum Application { ABSENCES, AGENDA, NOTES, SCOLARITE, TDBASE, TEXTES }

	private Map<String, Applications> allowedApplication;
	private final Map<String, String> roles = new HashMap<>();
	private long exportedDelay;
	private HttpClient client;
	private String appliCode;
	private PublicKey eliotPublicKey;
	private String logoutCallBack;
	private static final Pattern safariPattern =
			Pattern.compile("^.* Version/[0-9\\.]+ (Mobile/[A-Z0-9]+ )?Safari/[0-9\\.]+$");

	public static final String SCOLARITE_EXTERNAL_ID = "SCOLARITE";
	public static final JsonObject SCOLARITE = new JsonObject()
			.put("externalId", SCOLARITE_EXTERNAL_ID)
			.put("name", "SCOLARITE");


	@Override
	public void init(Vertx vertx, JsonObject config, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, config, rm, securedActions);
		exportedDelay = config.getLong("exported-delay", 5 * 60 * 1000l);
		appliCode = config.getString("appli-code");
		logoutCallBack = config.getString("logoutCallback");
		LocalMap<Object, Object> server = vertx.sharedData().getLocalMap("server");
		Boolean cluster = (Boolean) server.get("cluster");
		String node = (String) server.get("node");
		if (Boolean.TRUE.equals(cluster) && config.getBoolean("cluster", false)) {
			ClusterManager cm = ((VertxInternal) vertx).getClusterManager();
			allowedApplication = cm.getSyncMap("eliot");
		} else {
			allowedApplication = new HashMap<>();
		}
		try {
			URI uri = new URI(config.getString("uri"));
			HttpClientOptions options = new HttpClientOptions()
					.setDefaultHost(uri.getHost())
					.setDefaultPort(uri.getPort())
					.setMaxPoolSize(16)
					.setSsl("https".equals(uri.getScheme()))
					.setKeepAlive(false);
			client = vertx.createHttpClient(options);
		} catch (URISyntaxException e) {
			log.error(e.getMessage(), e);
		}
		final String publicKey = config.getString("eliot-public-key");
		if (isNotEmpty(publicKey)) {
			try {
				X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey));
				KeyFactory kf = KeyFactory.getInstance("RSA");
				eliotPublicKey = kf.generatePublic(spec);
			} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
				log.error(e.getMessage(), e);
			}
		}

		String defaultSyncCron = "0 45 23 * * ? *";
		if (Boolean.TRUE.equals(cluster) && node != null && !node.trim().isEmpty()) {
			try {
				if (Integer.parseInt(node.replaceAll("[A-Za-z]+", "")) % 2 == 0) {
					vertx.setTimer(120000, new Handler<Long>() {
						@Override
						public void handle(Long aLong) {
							configureApplications(null);
						}
					});
					defaultSyncCron = "0 50 23 * * ? *";
				} else {
					configureApplications(null);
				}
			} catch (NumberFormatException e) {
				configureApplications(null);
			}
		} else {
			configureApplications(null);
		}
		final String syncCron = config.getString("syncCron", defaultSyncCron);
		try {
			new CronTrigger(vertx, syncCron).schedule(new Handler<Long>() {
				@Override
				public void handle(Long event) {
					configureApplications(null);
				}
			});
		} catch (ParseException e) {
			log.error("Error parsing sync cron expression.", e);
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
		safariCookie(request, new Handler<Boolean>() {
			@Override
			public void handle(Boolean execute) {
				if (Boolean.TRUE.equals(execute)) {
					getRne(request, application, new Handler<String>() {

						@Override
						public void handle(String rne) {
							if (rne != null) {
								final StringBuilder uri = new StringBuilder();
								final String host = getScheme(request) + "://" + getHost(request);
								uri.append("/adapter?eliot=" + application.name().toLowerCase() + "#")
										.append(host)
										.append("/cas/login?ticketAttributeName=casTicket&service=");
								final StringBuilder eliotUri = new StringBuilder();
								try {
									eliotUri.append(config.getString("eliotUri"))
											.append("&rne=").append(rne)
											.append("&module=").append(application.name())
											.append("&hostCAS=").append(URLEncoder.encode(host + "/cas", "UTF-8"));
									uri.append(URLEncoder.encode(eliotUri.toString(), "UTF-8"));
									if (isNotEmpty(logoutCallBack)) {
										UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
											@Override
											public void handle(UserInfos user) {
												if (user != null) {
													CookieHelper.set("logoutCallback", logoutCallBack, request);
													UserUtils.removeSessionAttribute(eb, user.getUserId(),
															THEME_ATTRIBUTE + getHost(request),
															new Handler<Boolean>() {
														@Override
														public void handle(Boolean event) {
															redirect(request, uri.toString());
														}
													});
												} else {
													redirect(request, uri.toString());
												}
											}
										});
									} else {
										redirect(request, uri.toString());
									}
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
			}
		});
	}

	private void safariCookie(final HttpServerRequest request, final Handler<Boolean> handler) {
		final String scc = request.params().get("safariCookieCallback");
		if (scc != null) {
			UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
				@Override
				public void handle(UserInfos user) {
					if (user != null) {
						UserUtils.addSessionAttribute(eb, user.getUserId(), "safariEliotCookie", scc, null);
					}
				}
			});
			handler.handle(true);
			return;
		}
		if (eliotPublicKey != null && request.headers().get("User-Agent") != null &&
				safariPattern.matcher(request.headers().get("User-Agent")).matches()) {
			UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
				@Override
				public void handle(UserInfos user) {
					if (user != null) {
						if (user.getAttribute("safariEliotCookie") == null) {
							try {
								final String callbackUri = getScheme(request) + "://" + getHost(request) +
										request.uri() + (!request.uri().contains("?") ? "?":"&") +
										"safariCookieCallback=true";
								final Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
								c.init(Cipher.ENCRYPT_MODE, eliotPublicKey);
								final String eUri = URLEncoder.encode(Base64.getEncoder().encodeToString(
										c.doFinal(callbackUri.getBytes("UTF-8"))), "UTF-8");
								final String uri =
										"/eliot-saas-util/action/utils/domainUtils" +
										"?rUrl=" + eUri + "&t=" + System.currentTimeMillis();
								redirect(request, config.getString("uri"), uri);
							} catch (Exception e) {
								log.error("Error encrypting rsa eliot safari url");
								renderError(request);
							}
							handler.handle(false);
						} else {
							handler.handle(true);
						}
					} else {
						unauthorized(request, "invalid.userInfos");
						handler.handle(false);
					}
				}
			});
		} else {
			handler.handle(true);
		}
	}

	private void getRne(final HttpServerRequest request, final Application application,
			final Handler<String> handler) {
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
			@Override
			public void handle(UserInfos user) {
				boolean allowRecheck = false;
				if (user != null) {
					final Collection<String> structures;
					if (user.getFunctions() != null &&
							("Teacher".equals(user.getType()) || "Personnel".equals(user.getType()))) {
						structures = new HashSet<>();
						for (UserInfos.Function f : user.getFunctions().values()) {
							if (!"-".equals(f.getCode()) && f.getFunctionName() != null) {
								structures.addAll(f.getScope());
							}
						}
						allowRecheck = true;
					} else {
						structures = user.getStructures();
					}
					if (structuresToRne(structures)) return;
				}
				if (allowRecheck && structuresToRne(user.getStructures())) return;
				deny(request);
			}

			private boolean structuresToRne(Collection<String> structures) {
				for (String structure : structures) {
					Applications apps = allowedApplication.get(structure);
					if (apps != null && apps.getRne() != null) {
						for (fr.wseduc.eliot.pojo.Application app : apps.getApplications()) {
							if (application.name().equals(app.getCode())) {
								handler.handle(apps.getRne());
								return true;
							}
						}
					}
				}
				return false;
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
				setScolariteGroups(new Handler<Void>() {
					@Override
					public void handle(Void v) {
						exported(message);
					}
				});
				break;
			default:
				sendError(message, "invalid.action");
		}
	}

	private void setScolariteGroups(final Handler<Void> handler) {
		String query =
				"MATCH (p:Profile {name:'Personnel'})<-[:COMPOSE]-(f:Function {externalId : {functionEID}}) " +
				"RETURN count(*) > 0 as exists ";
		JsonObject params = new JsonObject().put("functionEID", SCOLARITE_EXTERNAL_ID);
		neo4j.execute(query, params, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				JsonArray res = message.body().getJsonArray("result");
				if ("ok".equals(message.body().getString("status")) && res != null && res.size() == 1 &&
						res.getJsonObject(0).getBoolean("exists", false)) {
					addScolariteFunction(handler);
				} else {
					String query =
							"MATCH (p:Profile { name : 'Personnel'}) " +
							"CREATE p<-[:COMPOSE]-(f:Function {props})";
					JsonObject params = new JsonObject().put("props", SCOLARITE);
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

			private void addScolariteFunction(final Handler<Void> handler) {
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
						JsonArray res = message.body().getJsonArray("result");
						if ("ok".equals(message.body().getString("status")) && res != null) {
							StatementsBuilder statements = new StatementsBuilder();
							for (Object o: res) {
								if (!(o instanceof JsonObject)) continue;
								JsonObject j = (JsonObject) o;
								addFunction(statements, j.getString("structureId"), j.getJsonArray("users"));
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

				JsonObject params = new JsonObject().put("users", users).put("scope", structureId);

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
						.put("scopeId", structureId)
						.put("functionCode", "SCOLARITE")
						.put("externalId", extId);
				statements.add(q2, p2);
				JsonObject pu = new JsonObject()
						.put("users", users)
						.put("externalId", extId);
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
					if (config.getBoolean("sunday-only", true)) {
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
		final Handler[] handlers = new Handler[i + 1];
		handlers[i] = new Handler<Void>() {
			@Override
			public void handle(Void v) {
				handler.handle(true);
			}
		};
		for (final String app : apps) {
			final int j = --i;
			handlers[j] = new Handler<Void>() {
				@Override
				public void handle(Void v) {
					JsonObject application = new JsonObject()
							.put("name", app)
							.put("displayName", app.toLowerCase())
							.put("address", "/eliot/" + app.toLowerCase());
					JsonObject message = new JsonObject()
							.put("application", application)
							.put("action", "create-external-application");
					eb.send("wse.app.registry.bus", message, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error(event.body().getString("message"));
								handler.handle(false);
							} else {
								handlers[j + 1].handle(null);
							}
						}
					}));
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
		final Handler[] handlers = new Handler[i + 1];
		handlers[i] = new Handler<Void>() {
			@Override
			public void handle(Void v) {
				JsonObject listRolesMessage = new JsonObject()
						.put("action", "list-roles");
				eb.send("wse.app.registry.bus", listRolesMessage, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> event) {
						JsonArray result = event.body().getJsonArray("result");
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
				}));
			}
		};
		for (final String application: apps) {
			final int j = --i;
			handlers[j] = new Handler<Void>() {
				@Override
				public void handle(Void v) {
					JsonObject role = new JsonObject()
							.put("name", application);
					JsonArray actions = new JsonArray()
							.add(application + "|address")
							.add(this.getClass().getName() + "|" + application.toLowerCase());
					final JsonObject message = new JsonObject()
							.put("action", "create-role")
							.put("role", role)
							.put("actions", actions);
					eb.send("wse.app.registry.bus", message, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error(event.body().getString("message"));
								handler.handle(false);
							} else {
								handlers[j + 1].handle(null);
							}
						}
					}));
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
		final Handler[] handlers = new Handler[i + 1];
		handlers[i] = new Handler<Void>() {
			@Override
			public void handle(Void v) {
				handler.handle(true);
			}
		};
		for (final Map.Entry<String, Applications> entry : allowedApplication.entrySet()) {
			final int j = --i;
			handlers[j] = new Handler<Void>() {
				@Override
				public void handle(Void v) {
					JsonObject message = new JsonObject()
							.put("action", "list-groups-with-roles")
							.put("structureId", entry.getKey());
					eb.send("wse.app.registry.bus", message, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
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
					}));
				}
			};
		}
		handlers[0].handle(null);
	}

	private void linkRolesToGroup(Message<JsonObject> event,
			final Map.Entry<String, Applications> entry, final Handler<Boolean> handler) {
		JsonArray result = event.body().getJsonArray("result");
		int k = result.size();
		final Handler[] handlers = new Handler[k + 1];
		handlers[k] = new Handler<Void>() {
			@Override
			public void handle(Void v) {
				handler.handle(true);
			}
		};
		for (final Object o : result) {
			final int l = --k;
			handlers[l] = new Handler<Void>() {
				@Override
				public void handle(Void v) {
					if (o instanceof JsonObject) {
						JsonObject j = (JsonObject) o;
						JsonObject message = new JsonObject()
								.put("action", "link-role-group")
								.put("groupId", j.getString("id"));
						List r = j.getJsonArray("roles").getList();
						r.removeAll(roles.values());
						JsonArray roleIds = new JsonArray(r);
						for (fr.wseduc.eliot.pojo.Application app : entry.getValue().getApplications()) {
							if (!Application.SCOLARITE.name().equals(app.getCode()) ||
									(Application.SCOLARITE.name().equals(app.getCode()) &&
											j.getString("name", "").contains("-SCOLARITE"))) {
								roleIds.add(roles.get(app.getCode()));
							}
						}
						message.put("roleIds", roleIds);
						log.debug(message.encodePrettily());
						eb.send("wse.app.registry.bus", message, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
							@Override
							public void handle(Message<JsonObject> event) {
								if ("ok".equals(event.body().getString("status"))) {
									handlers[l + 1].handle(null);
								} else {
									log.error(event.body().getString("message"));
									handler.handle(false);
								}
							}
						}));
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
		final JsonArray activeRne = new JsonArray();
		for (Object s : structures) {
			if (!(s instanceof JsonObject)) continue;
			final String structure = ((JsonObject) s).getString("id");
			final String rne = ((JsonObject) s).getString("UAI");
			log.info(config.getString("uri"));
			log.info(baseUri + rne);
			HttpClientRequest req = client.get(baseUri + rne, new Handler<HttpClientResponse>() {
				@Override
				public void handle(HttpClientResponse event) {
					if (event.statusCode() == 200) {
						activeRne.add(rne);
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
										persistActiveRne(activeRne);
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
							persistActiveRne(activeRne);
						}
					}
				}
			});
			req.exceptionHandler(except ->  log.error("Exception when call Eliot webservice", except));
			req.end();
		}
	}

	private void persistActiveRne(JsonArray activeRne) {
		if (activeRne == null || activeRne.size() == 0) return;
		final JsonObject params = new JsonObject().put("activeRne", activeRne);
		StatementsBuilder sb = new StatementsBuilder()
				.add(
						"MATCH (s:Structure) " +
						"WHERE s.UAI IN {activeRne} AND NOT('ELIOT' IN s.exports) " +
						"SET s.exports = coalesce(s.exports, []) + 'ELIOT' ", params)
				.add(
						"MATCH (s:Structure) " +
						"WHERE HAS(s.UAI) AND NOT(s.UAI IN {activeRne}) AND 'ELIOT' IN s.exports " +
						"SET s.exports = FILTER(e IN s.exports WHERE e <> 'ELIOT') ", params);
		neo4j.executeTransaction(sb.build(), null, true, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if (!"ok".equals(event.body().getString("status"))) {
					log.error("Error setting Eliot active RNE : " + event.body().getString("message"));
				}
			}
		});
	}

	private void getStructures(final Handler<JsonArray> structures) {
		JsonObject jo = new JsonObject();
		jo.put("action", "list-structures");
		jo.put("fields", new JsonArray().add("id").add("UAI"));
		eb.send("directory", jo, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray result = event.body().getJsonArray("result");
				if ("ok".equals(event.body().getString("status")) && result != null && result.size() > 0) {
					structures.handle(result);
				} else {
					structures.handle(null);
				}
			}
		}));
	}

	private void sendError(Message<JsonObject> message, String s) {
		log.error(s);
		message.reply(new JsonObject()
				.put("status", "error")
				.put("message", s)
		);
	}

	private void sendOK(Message<JsonObject> message) {
		message.reply(new JsonObject().put("status", "ok"));
	}

}
