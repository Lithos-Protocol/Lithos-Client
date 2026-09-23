package controllers

import play.api.Environment
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/**
 * Serves the web panel and docs: a Docusaurus build in `public/`, mounted at /assets.
 *
 * The site routes on the client, so its URLs name pages rather than files — /assets/mining/payments
 * is `mining/payments/index.html`. Following a link never asks the server for that URL; a refresh or a
 * pasted link does, and a literal file lookup finds a directory and answers 404. A path without a
 * file extension is therefore resolved to the page it names, and a page that does not exist gets the
 * site's own 404 page, which renders its not-found view.
 */
@Singleton
class WebPanelController @Inject()(assets: Assets, environment: Environment, cc: ControllerComponents)
                                  (implicit ec: ExecutionContext) extends AbstractController(cc) {

  def page(file: String): Action[AnyContent] = {
    val path = file.stripPrefix("/").stripSuffix("/")
    val name = path.split('/').last
    // Assets refuses to leave `public/` itself, but the existence check below must not look outside it either.
    if (path.split('/').contains("..")) notFound
    else if (name.contains('.')) assets.versioned("/public", Assets.Asset(path))
    else Seq(if (path.isEmpty) "index.html" else s"$path/index.html", s"$path.html").find(exists) match {
      case Some(page) => assets.at("/public", page)
      case None => notFound
    }
  }

  private def exists(file: String): Boolean = environment.resource(s"public/$file").isDefined

  private def notFound: Action[AnyContent] = Action.async { request =>
    assets.at("/public", "404.html")(request).map(result => result.copy(header = result.header.copy(status = NOT_FOUND)))
  }
}
