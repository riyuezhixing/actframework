package org.osgl.oms.route;

import org.osgl._;
import org.osgl.http.H;
import org.osgl.http.util.Path;
import org.osgl.mvc.result.NotFound;
import org.osgl.oms.app.AppContext;
import org.osgl.oms.ParamNames;
import org.osgl.oms.action.ActionHandler;
import org.osgl.oms.action.ActionHandlerResolver;
import org.osgl.oms.action.ActionHandlerResolverBase;
import org.osgl.oms.action.builtin.ControllerProxy;
import org.osgl.oms.action.builtin.Echo;
import org.osgl.oms.action.builtin.Redirect;
import org.osgl.oms.action.builtin.StaticFileGetter;
import org.osgl.oms.conf.AppConfig;
import org.osgl.util.*;

import java.io.PrintStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Router {

    private static final NotFound NOT_FOUND = new NotFound();
    private static final H.Method[] targetMethods = new H.Method[]{H.Method.GET, H.Method.POST, H.Method.PUT,
                                                                   H.Method.DELETE};

    private Node _GET = Node.newRoot();
    private Node _PUT = Node.newRoot();
    private Node _POST = Node.newRoot();
    private Node _DEL = Node.newRoot();

    private Map<String, ActionHandlerResolver> resolvers = C.newMap();

    private ActionHandlerResolver controllerLookup;
    private C.Set<String> controllerActionNames = C.newSet();
    private AppConfig appConfig;

    private void initControllerLookup(ActionHandlerResolver lookup) {
        if (null == lookup) {
            lookup = new ActionHandlerResolverBase() {
                @Override
                public ActionHandler resolve(CharSequence payload) {
                    return new ControllerProxy(payload.toString());
                }
            };
        }
        controllerLookup = lookup;
    }

    public Router(AppConfig config) {
        this(null, config);
    }

    public Router(ActionHandlerResolver controllerLookup, AppConfig config) {
        E.NPE(config);
        initControllerLookup(controllerLookup);
        this.appConfig = config;
    }

    // --- routing ---
    public ActionHandler getInvoker(H.Method method, CharSequence path, AppContext context) {
        Node node = search(method, Path.tokenizer(Unsafe.bufOf(path)), context);
        return getInvokerFrom(node);
    }

    public ActionHandler getInvoker(H.Method method, List<CharSequence> path, AppContext context) {
        Node node = search(method, path, context);
        return getInvokerFrom(node);
    }

    private ActionHandler getInvokerFrom(Node node) {
        if (null == node) {
            throw NOT_FOUND;
        }
        ActionHandler handler = node.handler;
        if (null == handler) {
            throw NOT_FOUND;
        }
        return handler;
    }

    // --- route building ---
    public boolean isMapped(H.Method method, CharSequence path) {
        return null != _locate(method, path);
    }

    public void addMapping(H.Method method, CharSequence path, ActionHandler handler) {
        Node node = _locate(method, path);
        node.handler(handler);
    }

    public void addMapping(H.Method method, CharSequence path, CharSequence action) {
        ActionHandler handler = resolveActionHandler(action);
        addMapping(method, path, handler);
    }

    public void addMappingIfNotMapped(H.Method method, CharSequence path, ActionHandler handler) {
        Node node = _locate(method, path);
        if (null == node.handler) {
            node.handler(handler);
        }
    }

    public void addMappingIfNotMapped(H.Method method, CharSequence path, CharSequence action) {
        Node node = _locate(method, path);
        if (null == node.handler) {
            node.handler(resolveActionHandler(action));
        }
    }

    private Node _locate(H.Method method, CharSequence path) {
        Node node = root(method);
        assert node != null;
        E.unsupportedIf(null == node, "Method %s is not supported", method);
        if (path.length() == 1 && path.charAt(0) == '/') {
            return node;
        }
        String sUrl = path.toString();
        List<CharSequence> paths = Path.tokenize(Unsafe.bufOf(sUrl));
        int len = paths.size();
        for (int i = 0; i < len - 1; ++i) {
            node = node.addChild((StrBase) paths.get(i));
        }
        return node.addChild((StrBase) paths.get(len - 1));
    }

    // --- action handler resolving

    /**
     * Register 3rd party action handler resolver with specified directive
     *
     * @param directive
     * @param resolver
     */
    public void registerActionHandlerResolver(String directive, ActionHandlerResolver resolver) {
        resolvers.put(directive, resolver);
    }

    // -- action method sensor
    public boolean isActionMethod(String className, String methodName) {
        String action = new StringBuilder(className).append(".").append(methodName).toString();
        String controllerPackage = appConfig.controllerPackage();
        if (S.notEmpty(controllerPackage)) {
            if (action.startsWith(controllerPackage)) {
                String action2 = action.substring(controllerPackage.length() + 1);
                if (controllerActionNames.contains(action2)) {
                    return true;
                }
            }
        }
        return controllerActionNames.contains(action);
    }

    public void debug(PrintStream ps) {
        for (H.Method method : supportedHttpMethods()) {
            Node node = root(method);
            node.debug(method, ps);
        }
    }

    public static H.Method[] supportedHttpMethods() {
        return targetMethods;
    }

    private Node search(H.Method method, List<CharSequence> path, AppContext context) {
        Node node = root(method);
        int sz = path.size();
        int i = 0;
        while (null != node && i < sz) {
            CharSequence nodeName = path.get(i++);
            node = node.child(nodeName, context);
            if (null != node && node.terminateRouteSearch()) {
                if (i == sz) {
                    context.param(ParamNames.PATH, "");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i; j < sz; ++j) {
                        sb.append('/').append(path.get(j));
                    }
                    context.param(ParamNames.PATH, sb.toString());
                }
                break;
            }
        }
        return node;
    }

    private Node search(H.Method method, Iterator<CharSequence> path, AppContext context) {
        Node node = root(method);
        while (null != node && path.hasNext()) {
            CharSequence nodeName = path.next();
            node = node.child(nodeName, context);
            if (null != node && node.terminateRouteSearch()) {
                if (!path.hasNext()) {
                    context.param(ParamNames.PATH, "");
                } else {
                    StringBuilder sb = new StringBuilder();
                    while (path.hasNext()) {
                        sb.append('/').append(path.next());
                    }
                    context.param(ParamNames.PATH, sb.toString());
                }
                break;
            }
        }
        return node;
    }

    private ActionHandler resolveActionHandler(CharSequence action) {
        _.T2<String, String> t2 = splitActionStr(action);
        String directive = t2._1, payload = t2._2;

        if (S.notEmpty(directive)) {
            ActionHandlerResolver resolver = resolvers.get(action);
            ActionHandler handler = null == resolver ?
                    BuiltInHandlerResolver.tryResolve(directive, payload) :
                    resolver.resolve(payload);
            E.unsupportedIf(null == handler, "cannot find action handler by directive: %s", directive);
            return handler;
        } else {
            ActionHandler handler = controllerLookup.resolve(payload);
            E.unsupportedIf(null == handler, "cannot find action handler: %s", action);
            controllerActionNames.add(payload);
            return handler;
        }
    }

    private _.T2<String, String> splitActionStr(CharSequence action) {
        FastStr fs = FastStr.of(action);
        FastStr fs1 = fs.beforeFirst(':');
        FastStr fs2 = fs1.isEmpty() ? fs : fs.substr(fs1.length() + 1);
        return _.T2(fs1.trim().toString(), fs2.trim().toString());
    }

    private Node root(H.Method method) {
        switch (method) {
            case GET:
                return _GET;
            case POST:
                return _POST;
            case PUT:
                return _PUT;
            case DELETE:
                return _DEL;
            default:
                throw E.unexpected("HTTP Method not supported: %s", method);
        }
    }

    /**
     * The data structure support decision tree for
     * fast URL routing
     */
    private static class Node implements Serializable {
        static final Node newRoot() {
            return new Node(-1);
        }

        private int id;
        private StrBase name;
        private Pattern pattern;
        private CharSequence varName;
        private Node parent;
        private Node dynamicChild;
        private C.Map<CharSequence, Node> staticChildren = C.newMap();
        private ActionHandler handler;

        private Node(int id) {
            this.id = id;
            name = FastStr.EMPTY_STR;
        }

        Node(StrBase name, Node parent) {
            E.NPE(name);
            this.name = name;
            this.parent = parent;
            this.id = name.hashCode();
            parseDynaName(name);
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj instanceof Node) {
                Node that = (Node) obj;
                return that.id == id && that.name.equals(name);
            }
            return false;
        }

        public boolean isDynamic() {
            return null != varName;
        }

        boolean metaInfoMatches(StrBase string) {
            _.T2<StrBase, Pattern> result = _parseDynaName(string);
            if (pattern != null && result._2 != null) {
                return pattern.pattern().equals(result._2.pattern());
            } else {
                // just allow route table to use different var names
                return true;
            }
        }

        public boolean matches(CharSequence chars) {
            if (!isDynamic()) return name.contentEquals(chars);
            if (null != pattern) {
                return pattern.matcher(chars).matches();
            }
            return true;
        }

        public Node child(CharSequence name, AppContext context) {
            Node node = staticChildren.get(name);
            if (null == node && null != dynamicChild) {
                if (dynamicChild.matches(name)) {
                    context.param(dynamicChild.varName.toString(), name.toString());
                    return dynamicChild;
                }
            }
            return node;
        }

        Node childByMetaInfo(StrBase s) {
            Node node = staticChildren.get(s);
            if (null == node && null != dynamicChild) {
                if (dynamicChild.metaInfoMatches(s)) {
                    return dynamicChild;
                }
            }
            return node;
        }

        Node addChild(StrBase<?> name) {
            name = name.trim();
            Node child = childByMetaInfo(name);
            if (null != child) {
                return child;
            }
            child = new Node(name, this);
            if (child.isDynamic()) {
                E.unexpectedIf(null != dynamicChild, "Cannot have more than one dynamic node in the route tree: %s", name);
                dynamicChild = child;
            } else {
                staticChildren.put(name, child);
            }
            return child;
        }

        Node handler(ActionHandler handler) {
            E.NPE(handler);
            this.handler = handler;
            return this;
        }

        ActionHandler handler() {
            return this.handler;
        }

        boolean terminateRouteSearch() {
            return null != handler && handler.supportPartialPath();
        }

        String path() {
            if (null == parent) return "/";
            String pPath = parent.path();
            return new StringBuilder(pPath).append(pPath.endsWith("/") ? "" : "/").append(name).toString();
        }

        void debug(H.Method method, PrintStream ps) {
            if (null != handler) {
                ps.printf("%s %s %s\n", method, path(), handler);
            }
            for (Node node : staticChildren.values()) {
                node.debug(method, ps);
            }
            if (null != dynamicChild) {
                dynamicChild.debug(method, ps);
            }
        }

        private void parseDynaName(StrBase name) {
            _.T2<StrBase, Pattern> result = _parseDynaName(name);
            if (null != result) {
                this.varName = result._1;
                this.pattern = result._2;
            }
        }

        private static _.T2<StrBase, Pattern> _parseDynaName(StrBase name) {
            name = name.trim();
            if (name.startsWith("{") && name.endsWith("}")) {
                StrBase s = name.afterFirst('{').beforeLast('}').trim();
                if (s.contains('<') && s.contains('>')) {
                    StrBase varName = s.afterLast('>').trim();
                    StrBase ptn = s.afterFirst('<').beforeLast('>').trim();
                    Pattern pattern = Pattern.compile(ptn.toString());
                    return _.T2(varName, pattern);
                } else {
                    return _.T2(s, null);
                }
            }
            return null;
        }
    }

    private static enum BuiltInHandlerResolver implements ActionHandlerResolver {
        echo() {
            @Override
            public ActionHandler resolve(CharSequence msg) {
                return new Echo(msg.toString());
            }
        },
        redirect() {
            @Override
            public ActionHandler resolve(CharSequence payload) {
                return new Redirect(payload.toString());
            }
        },
        staticdir() {
            @Override
            public ActionHandler resolve(CharSequence base) {
                return new StaticFileGetter(base.toString());
            }
        },
        staticfile() {
            @Override
            public ActionHandler resolve(CharSequence base) {
                return new StaticFileGetter(base.toString(), true);
            }
        };

        private static ActionHandler tryResolve(CharSequence directive, CharSequence payload) {
            String s = directive.toString().toLowerCase();
            try {
                return valueOf(s).resolve(payload);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

}
