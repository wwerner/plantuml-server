/* ========================================================================
 * PlantUML : a free UML diagram generator
 * ========================================================================
 *
 * Project Info:  https://plantuml.com
 *
 * This file is part of PlantUML.
 *
 * PlantUML is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlantUML distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 */
package net.sourceforge.plantuml.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.List;
import java.util.stream.Collectors;

import javax.imageio.IIOException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.OptionFlags;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.core.UmlSource;

/**
 * Proxy servlet of the webapp.
 * This servlet retrieves the diagram source of a web resource (web html page)
 * and renders it.
 */
@SuppressWarnings("SERIAL")
public class ProxyServlet extends HttpServlet {

    static {
        OptionFlags.ALLOW_INCLUDE = false;
        if ("true".equalsIgnoreCase(System.getenv("ALLOW_PLANTUML_INCLUDE"))) {
            OptionFlags.ALLOW_INCLUDE = true;
        }
    }

    private static final String PU_INCLUDE_OP = "!include ";
    private static final String PU_INCLUDEURL_OP = "!includeurl ";

    private static String rewriteInclude(URL docSrc, String line) {

        // ignore invalid lines; could also throw IAE

        // rewrite includes
        /*
        1. parse url to find common prefix -> base
        2. for each line that is an !include w/ relative path
        2.1. get filename by removing !include
        2.2. generate url for included file:  base + included filename -> url
        3. replace '!include file' w/ '!includeurl url'
        4. think about security first :-) ¯\_(ツ)_/¯
        */
        if (line == null || !line.trim().startsWith(PU_INCLUDE_OP)) {
            return line;
        }


        String prefix = docSrc.toExternalForm();
        prefix = prefix.substring(0, prefix.lastIndexOf('/'));


        String path = line.substring(PU_INCLUDE_OP.length());

        String proxy = "https://8071-178-27-145-111.ngrok.io/plantuml/proxy"; // calculate or get from env //FIXME

        // String.format("%s %s?cache=no&format=preproc&src=%s/%s", PU_INCLUDEURL_OP, proxy,prefix, path)
        return PU_INCLUDEURL_OP
                + " "
                + proxy
                + "?cache=no"
                + "&fmt=preproc"
                + "&src="
                + prefix + "/" + path;

    }

    private static boolean isGitHubUrl(String url) {
        return url.contains("//github.com/") || url.contains("//www.github.com/");
    }

    private static String toGitHubRawUrl(String url) {
        return url
                .replace("//github.com/", "//raw.githubusercontent.com/")
                .replace("//www.github.com/", "//raw.githubusercontent.com/")
                .replace("/blob/", "/");
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

        final String fmt = request.getParameter("fmt");
        final String index = request.getParameter("idx");
        final String rawSource = request.getParameter("src");
        final String source;


        boolean rewriteGitHubRaw = true; // FIXME: move to env var
        if (rewriteGitHubRaw && isGitHubUrl(rawSource)) {
            source = toGitHubRawUrl(rawSource);
        }  else {
            source = rawSource;
        }

        System.out.printf(
                "Proxying request to '%s' as '%s' from '%s'\n",
                request.getHeader("referer"),
                fmt,
                source);

        final URL srcUrl;
        // Check if the src URL is valid
        try {
            srcUrl = new URL(source);
        } catch (MalformedURLException mue) {
            mue.printStackTrace();
            response.setStatus(400);
            return;
        }

        // generate the response
        String diagmarkup = getSource(srcUrl);

        // rewrite includes to go through the proxy as well
        diagmarkup = diagmarkup.lines()
                .map(l -> l.trim().startsWith(PU_INCLUDE_OP)
                        ? ProxyServlet.rewriteInclude(srcUrl, l)
                        : l)
                .collect(Collectors.joining("\n"));

        SourceStringReader reader = new SourceStringReader(diagmarkup);
        int n = index == null ? 0 : Integer.parseInt(index);
        List<BlockUml> blocks = reader.getBlocks();
        BlockUml block = blocks.get(n);
        Diagram diagram = block.getDiagram();
        UmlSource umlSrc = diagram.getSource();
        String uml = umlSrc.getPlainString();
        //System.out.println("uml=" + uml);

        // generate the response
        DiagramResponse dr = new DiagramResponse(response, getOutputFormat(fmt), request);
        try {
            dr.sendDiagram(uml, 0);
        } catch (IIOException e) {
            // Browser has closed the connection, so the HTTP OutputStream is closed
            // Silently catch the exception to avoid annoying log
        }
        dr = null;
    }

    /**
     * Get textual uml diagram source from URL.
     *
     * @param url source URL
     * @return textual uml diagram source
     * @throws IOException if an input or output exception occurred
     */
    private String getSource(final URL url) throws IOException {
        String line;
        BufferedReader rd;
        StringBuilder sb;
        try {
            HttpURLConnection con = getConnection(url);
            rd = new BufferedReader(new InputStreamReader(con.getInputStream()));
            sb = new StringBuilder();

            while ((line = rd.readLine()) != null) {
                sb.append(line + '\n');
            }
            rd.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            rd = null;
        }
        return "";
    }

    /**
     * Get {@link FileFormat} instance from string.
     *
     * @param format file format name
     * @return corresponding file format instance,
     * if {@code format} is null or unknown the default {@link FileFormat#PNG} will be returned
     */
    private FileFormat getOutputFormat(String format) {
        if (format == null) {
            return FileFormat.PNG;
        }
        if (format.equals("svg")) {
            return FileFormat.SVG;
        }
        if (format.equals("eps")) {
            return FileFormat.EPS;
        }
        if (format.equals("epstext")) {
            return FileFormat.EPS_TEXT;
        }
        if (format.equals("txt")) {
            return FileFormat.UTXT;
        }
        if (format.equals("preproc")) {
            return FileFormat.PREPROC;
        }
        return FileFormat.PNG;
    }

    /**
     * Get open http connection from URL.
     *
     * @param url URL to open connection
     * @return open http connection
     * @throws IOException if an input or output exception occurred
     */
    private HttpURLConnection getConnection(final URL url) throws IOException {
        final HttpURLConnection con = (HttpURLConnection) url.openConnection();
        //if (con instanceof HttpsURLConnection) {
        //    printHttpsCert((HttpsURLConnection) con);
        //}
        con.setRequestMethod("GET");
        String token = System.getenv("HTTP_AUTHORIZATION");
        if (token != null) {
            con.setRequestProperty("Authorization", token);
        }
        con.setReadTimeout(10000); // 10 seconds
        con.connect();
        return con;
    }

    /**
     * Debug method used to dump the certificate info.
     *
     * @param con the https connection
     */
    @SuppressWarnings("unused")
    private void printHttpsCert(final HttpsURLConnection con) {
        if (con != null) {
            try {
                System.out.println("Response Code : " + con.getResponseCode());
                System.out.println("Cipher Suite : " + con.getCipherSuite());
                System.out.println("\n");

                Certificate[] certs = con.getServerCertificates();
                for (Certificate cert : certs) {
                    System.out.println("Cert Type : " + cert.getType());
                    System.out.println("Cert Hash Code : " + cert.hashCode());
                    System.out.println("Cert Public Key Algorithm : " + cert.getPublicKey().getAlgorithm());
                    System.out.println("Cert Public Key Format : " + cert.getPublicKey().getFormat());
                    System.out.println("\n");
                }

            } catch (SSLPeerUnverifiedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
