package app.alertify.jpa.audit;

import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@RevisionEntity(AuditRevisionListener.class)
@Table(name = "revinfo", schema = "audit")
public class AuditRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private Long revision;

    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long revisionTimestamp;

    @Column(name = "user_subject", nullable = false, length = 255)
    private String userSubject;

    @Column(nullable = false, length = 255)
    private String username;

    public Long getRevision() { return revision; }
    public long getRevisionTimestamp() { return revisionTimestamp; }
    public String getUserSubject() { return userSubject; }
    public String getUsername() { return username; }

    void setUserSubject(String userSubject) { this.userSubject = userSubject; }
    void setUsername(String username) { this.username = username; }
}
