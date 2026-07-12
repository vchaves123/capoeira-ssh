package br.com.capoeirassh.ssh.model;

/**
 * Icon types available to associate with a session, each with an icon PNG
 * (resources/icons/&lt;size&gt;/&lt;key&gt;.png) and an identifying color.
 *
 * SWT images (org.eclipse.swt.graphics.Image) allocate native OS resources and
 * must be disposed when no longer needed — see {@link br.com.capoeirassh.ssh.ui.SessionIconRegistry},
 * which caches and disposes them; never load an Image from these paths directly.
 */
public enum SessionIconType {

    LINUX("linux", "#f4a940", "Linux"),
    AIX("aix", "#4a90d9", "AIX"),
    SOLARIS("solaris", "#e8734a", "Solaris"),
    WINDOWS("windows", "#56b6e0", "Windows"),
    DATABASE_GENERIC("db", "#9d7cd8", "Banco de dados"),
    MAIL("mail", "#5ec98f", "Mail server"),
    WEB("web", "#45c5c0", "Web server"),
    NETWORK("network", "#d85c7a", "Rede / Firewall"),
    CONTAINER("docker", "#2fb7e0", "Container"),
    CLOUD("cloud", "#8fa8bd", "Cloud / VM"),
    BACKUP("backup", "#c9a83c", "Backup / Storage"),
    GENERIC("generic", "#8b98a5", "Generico"),
    MACOS("macos", "#b8bfc7", "macOS"),
    BSD("bsd", "#d1495b", "FreeBSD / BSD"),
    HPUX("hpux", "#7c9c4f", "HP-UX"),
    MAINFRAME("mainframe", "#6c7a89", "Mainframe (z/OS)"),
    KUBERNETES("k8s", "#3f6fbf", "Kubernetes / Cluster"),
    MYSQL("mysql", "#e0954a", "MySQL / MariaDB"),
    POSTGRES("postgres", "#3f8fc9", "PostgreSQL"),
    ORACLE("oracle", "#d9534f", "Oracle DB"),
    MONGODB("mongo", "#4caf6e", "MongoDB / NoSQL"),
    REDIS("redis", "#c0392b", "Redis / Cache"),
    VPN("vpn", "#8e6fd9", "VPN"),
    PROXY("proxy", "#56b6c2", "Proxy / Load Balancer"),
    DNS("dns", "#c9a23c", "DNS"),
    ROUTER("router", "#5fa8d3", "Roteador / Switch"),
    BASTION("bastion", "#d9a441", "VPN Gateway / Bastion"),
    APP_SERVER("appserver", "#7e57c2", "Application Server"),
    MESSAGE_QUEUE("mq", "#43a5c7", "Message Queue"),
    CICD("cicd", "#58b368", "CI/CD"),
    MONITORING("monitoring", "#e0637a", "Monitoramento"),
    LOG_SERVER("logs", "#9aa5b1", "Log Server"),
    SAN_NAS("san", "#b08d57", "SAN / NAS Storage"),
    HYPERVISOR("hypervisor", "#6f8faa", "Hypervisor"),
    TAPE_BACKUP("tape", "#8a7355", "Tape / Backup Arquivado"),
    IOT("iot", "#4fae8e", "IoT / Embedded");

    private final String key;
    private final String hexColor;
    private final String label;

    SessionIconType(String key, String hexColor, String label) {
        this.key = key;
        this.hexColor = hexColor;
        this.label = label;
    }

    /** Short key used to persist the type (e.g. in a session file). */
    public String getKey() { return key; }

    /** Identifying color in hex, e.g. "#f4a940". */
    public String getHexColor() { return hexColor; }

    /** Human-readable label for display in the UI. */
    public String getLabel() { return label; }

    /** Classpath path to the PNG for a given size (16, 24 or 32). */
    public String getIconPath(int size) {
        return "/icons/" + size + "/" + key + ".png";
    }

    /** Looks up the type from its persisted key (e.g. when loading a saved session). */
    public static SessionIconType fromKey(String key) {
        for (SessionIconType t : values()) {
            if (t.key.equals(key)) return t;
        }
        return GENERIC;
    }
}
