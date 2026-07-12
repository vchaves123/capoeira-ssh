package br.com.capoeirassh.ssh.model;

/**
 * Icon types available to associate with a session, each with an icon PNG
 * (resources/icons/&lt;size&gt;/&lt;key&gt;.png).
 *
 * SWT images (org.eclipse.swt.graphics.Image) allocate native OS resources and
 * must be disposed when no longer needed — see {@link br.com.capoeirassh.ssh.ui.SessionIconRegistry},
 * which caches and disposes them; never load an Image from these paths directly.
 */
public enum SessionIconType {

    LINUX("linux", "Linux"),
    AIX("aix", "AIX"),
    SOLARIS("solaris", "Solaris"),
    WINDOWS("windows", "Windows"),
    DATABASE_GENERIC("db", "Banco de dados"),
    MAIL("mail", "Mail server"),
    WEB("web", "Web server"),
    NETWORK("network", "Rede / Firewall"),
    CONTAINER("docker", "Container"),
    CLOUD("cloud", "Cloud / VM"),
    BACKUP("backup", "Backup / Storage"),
    GENERIC("generic", "Generico"),
    MACOS("macos", "macOS"),
    BSD("bsd", "FreeBSD / BSD"),
    HPUX("hpux", "HP-UX"),
    MAINFRAME("mainframe", "Mainframe (z/OS)"),
    KUBERNETES("k8s", "Kubernetes / Cluster"),
    MYSQL("mysql", "MySQL / MariaDB"),
    POSTGRES("postgres", "PostgreSQL"),
    ORACLE("oracle", "Oracle DB"),
    MONGODB("mongo", "MongoDB / NoSQL"),
    REDIS("redis", "Redis / Cache"),
    VPN("vpn", "VPN"),
    PROXY("proxy", "Proxy / Load Balancer"),
    DNS("dns", "DNS"),
    ROUTER("router", "Roteador / Switch"),
    BASTION("bastion", "VPN Gateway / Bastion"),
    APP_SERVER("appserver", "Application Server"),
    MESSAGE_QUEUE("mq", "Message Queue"),
    CICD("cicd", "CI/CD"),
    MONITORING("monitoring", "Monitoramento"),
    LOG_SERVER("logs", "Log Server"),
    SAN_NAS("san", "SAN / NAS Storage"),
    HYPERVISOR("hypervisor", "Hypervisor"),
    TAPE_BACKUP("tape", "Tape / Backup Arquivado"),
    IOT("iot", "IoT / Embedded");

    private final String key;
    private final String label;

    SessionIconType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Short key used to persist the type (e.g. in a session file). */
    public String getKey() { return key; }

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
