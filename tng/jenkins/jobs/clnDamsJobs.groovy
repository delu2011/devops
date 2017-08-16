String project        = 'cln'
String app            = 'dams'

String basePath       = "${project}-${app}"
String gitRepo        = 'git@github.com:genomicsengland/geldam-scripts.git'
String gitCheckout    = 'master'
String gitCredentials = 'deploy'
String phasedPath     = "$basePath/phased"

folder(basePath) {
    description "${project} - ${app} Related Builds"
}

job("$basePath/${app.toLowerCase()}-build") {
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    scm {
        git {
          remote {
              credentials(gitCredentials)
              name('origin')
              url(gitRepo)
          }
          branch(gitCheckout)
        }
    }
    steps {
        //shell './scripts/00_fetch_artifacts.sh'
        shell '''
chmod 0600 keys/key
echo 'get release/release.json' > sftp-batch
sftp -oIdentityFile=keys/key -b sftp-batch ssd-upload@ssd-sftp.extge.co.uk
        '''

        //shell './scripts/01_package_artifacts.sh release.json'
        shell '''
mkdir -p artifacts
mkdir -p artifacts/openclinica
mkdir -p artifacts/file-receiver
mkdir -p artifacts/sampletracking
mkdir -p artifacts/burst
mkdir -p artifacts/mercury
        '''
shell 'echo "$(jq -r .release release.json)"-$BUILD_ID > version.txt'
shell './scripts/02_download_binaries.sh release.json'
shell './scripts/03_package_labkey.sh release.json'
shell './scripts/99_create_tarball.sh'
shell 'VERSION="geldam-$(cat version.txt).tgz"'
shell 'mv geldam.tar.gz $VERSION'
shell 'cp $VERSION /var/www/repo'
  }
    publishers {
        chucknorris()
    }
}

        //------ -++++++++++++++++++++-----------

        //Deploy Auto

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-auto-1") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['test-damsauto'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-auto/phased/deploy-labkey')
        }
        shell '''
SM_HOST='cln-prod-core-services-01.gel.zone'
SM_TARGET="cln-${ENVIRONMENT}-frec-01.gel.zone, cln-${ENVIRONMENT}-lk-01.gel.zone, cln-${ENVIRONMENT}-oc-01.gel.zone"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"
        '''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST2', 'cln-${ENVIRONMENT}-brst-02.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
BURST_DB_USER='burst'
BURST_DB_PASS='m7181QqAJECz'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
BURST_RBT_USER="damsauto"
BURST_RBT_PASS="8fcuhKUg"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst
echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF


ssh deploy@${BURST_HOST2} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-lk-01.gel.zone"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
MERC_DB_PASS='kpeUMMdu97vq'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
MERC_RBT_PASS='8fcuhKUg'
MERC_RBT_PORT='5672'
MERC_RBT_USER='damsauto'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='2f23d4e4-3e21-46e3-a45f-302e63c62f2e'
PDS_LOOKUP_URL="https://portal-uat-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='eB48rXSmds'
PDS_USERNAME='genomics_user'

cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84-01.gel.zone"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-test-damsauto-oc-01.gel.zone:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
SAMTRA_DB_PASS='wcHPz6fBEwXW'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
SAMTRA_RBT_PASS='8fcuhKUg'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='damsauto'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

        //------ -++++++++++++++++++++-----------

        //Deploy Performance

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-perf") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['perf'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-perf/phased/deploy-labkey')
        }
        shell '''
SM_HOST='il2e-ms-sm1.cln.ge.local'
SM_TARGET="cln-${ENVIRONMENT}-frec01, cln-${ENVIRONMENT}-lk01, cln-${ENVIRONMENT}-oc01"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"
        '''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
BURST_DB_USER='burst'
BURST_DB_PASS='Sijai8ei'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
BURST_RBT_USER="gelperf"
BURST_RBT_PASS="jK5sAUkPg3"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst

echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-lk01.cln.ge.local"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
MERC_DB_PASS='phiiR7ji'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
MERC_RBT_PASS='jK5sAUkPg3'
MERC_RBT_PORT='5672'
MERC_RBT_USER='gelperf'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='2f23d4e4-3e21-46e3-a45f-302e63c62f2e'
PDS_LOOKUP_URL="https://portal-uat-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='eB48rXSmds'
PDS_USERNAME='genomics_user'

cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84.cln.ge.local"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-perf-oc01.cln.ge.local:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
SAMTRA_DB_PASS='rah9fooJ'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
SAMTRA_RBT_PASS='jK5sAUkPg3'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='gelperf'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

        //------ -++++++++++++++++++++-----------

        //Deploy Reference

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-reference") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['reference-dams'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-reference/phased/deploy-labkey')
        }
        shell '''
SM_HOST='il2e-ms-sm1.cln.ge.local'
SM_TARGET="cln-${ENVIRONMENT}-frec-01.gel.zone, cln-${ENVIRONMENT}-lk-01.gel.zone, cln-${ENVIRONMENT}-oc-01.gel.zone"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"
        '''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
BURST_DB_USER='burst'
BURST_DB_PASS='xLpdfPar'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
BURST_RBT_USER="clnref"
BURST_RBT_PASS="9WZMJ6R54T"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst

echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-lk-01.gel.zone"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
MERC_DB_PASS='5gn9dhT3'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
MERC_RBT_PASS='9WZMJ6R54T'
MERC_RBT_PORT='5672'
MERC_RBT_USER='clnref'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='654edc02-bd49-4c1b-8465-4de251651f89'
PDS_LOOKUP_URL="https://portal-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='sUswa!8uQaku'
PDS_USERNAME='genomics_user'

cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84-01.gel.zone"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-reference-oc-01.gel.zone:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
SAMTRA_DB_PASS='z9HTS4Nu'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
SAMTRA_RBT_PASS='9WZMJ6R54T'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='clnref'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

        //------ -++++++++++++++++++++-----------

        //Deploy UAT

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-reference") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['uat'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-uat/phased/deploy-labkey')
        }
        shell '''
SM_HOST='il2e-ms-sm1.cln.ge.local'
SM_TARGET="cln-${ENVIRONMENT}-frec-01.gel.zone, cln-${ENVIRONMENT}-lk-01.gel.zone, cln-${ENVIRONMENT}-oc-01.gel.zone"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"
        '''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST2', 'cln-${ENVIRONMENT}-brst-02.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
BURST_DB_USER='burst'
BURST_DB_PASS='m7181QqAJECz'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
BURST_RBT_USER="clnuat"
BURST_RBT_PASS="Mn7tYoN6V"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst

echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
scp burst.tar.gz deploy@${BURST_HOST2}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF

# Update Burst
ssh deploy@${BURST_HOST2} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-lk-01.gel.zone"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
MERC_DB_PASS='kpeUMMdu97vq'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
MERC_RBT_PASS='Mn7tYoN6V'
MERC_RBT_PORT='5672'
MERC_RBT_USER='clnuat'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='2f23d4e4-3e21-46e3-a45f-302e63c62f2e'
PDS_LOOKUP_URL="https://portal-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='eB48rXSmds'
PDS_USERNAME='genomics_user'


cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84.gel.zone"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-uat-dams-oc-01.gel.zone:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
SAMTRA_DB_PASS='wcHPz6fBEwXW'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
SAMTRA_RBT_PASS='Mn7tYoN6V'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='clnuat'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}


        //------ -++++++++++++++++++++-----------

        //Deploy Regression

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-regression") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['uat-dams'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-regression/phased/deploy-labkey')
        }
        shell '''
SM_HOST='cln-prod-core-services-01.gel.zone'
SM_TARGET="cln-${ENVIRONMENT}-frec-01.gel.zone, cln-${ENVIRONMENT}-lk-01.gel.zone, cln-${ENVIRONMENT}-oc-01.gel.zone"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"
        '''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST2', 'cln-${ENVIRONMENT}-brst-02.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
BURST_DB_USER='burst'
BURST_DB_PASS='m7181QqAJECz'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
BURST_RBT_USER="clnuat"
BURST_RBT_PASS="Mn7tYoN6V"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst

echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
scp burst.tar.gz deploy@${BURST_HOST2}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst1
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF

# Update Burst2
ssh deploy@${BURST_HOST2} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-lk-01.gel.zone"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
MERC_DB_PASS='kpeUMMdu97vq'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
MERC_RBT_PASS='Mn7tYoN6V'
MERC_RBT_PORT='5672'
MERC_RBT_USER='clnuat'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='2f23d4e4-3e21-46e3-a45f-302e63c62f2e'
PDS_LOOKUP_URL="https://portal-uat-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='eB48rXSmds'
PDS_USERNAME='genomics_user'

cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84-01.gel.zone"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-uat-dams-oc-01.gel.zone:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra-01.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94-01.gel.zone"
SAMTRA_DB_PASS='wcHPz6fBEwXW'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt-01.gel.zone"
SAMTRA_RBT_PASS='Mn7tYoN6V'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='clnuat'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

        //------ -++++++++++++++++++++-----------

        //Deploy Prod

        //------ -++++++++++++++++++++-----------


multiJob("$basePath/${app.toLowerCase()}-deploy-prod") {
    parameters {
        //stringParam(name,value,descrioption)
        choiceParam('ENVIRONMENT', ['prod'])
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
# Fetch latest artifact
wget http://localhost/repo/geldam-latest.tar.gz

# Rename artifact
mv geldam-latest.tar.gz geldam.tar.gz

# Extract Artifact
tar -xzf geldam.tar.gz
        '''
        phase('Deploy Services') {
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-burst')
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-frec')
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-mercury')
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-openclinica')
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-sample-tracking')
            phaseJob('cln-dams/cln-dams-prod/phased/deploy-labkey')
        }
        shell '''
SM_HOST='il2e-ms-sm1.cln.ge.local'
SM_TARGET="cln-${ENVIRONMENT}-frec01, cln-${ENVIRONMENT}-lk01, cln-${ENVIRONMENT}-oc01"
# Run Salt Highstate
ssh deploy@${SM_HOST} sudo "salt -L '${SM_TARGET}' --state-output=changes state.highstate queue=True"        
'''
    }
    publishers {
        chucknorris()
    }
}

// Phased Jobs
folder(phasedPath) {
    description "${project} - ${app} - phased Builds"
}

job("$phasedPath/deploy-burst") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST1', 'cln-${ENVIRONMENT}-brst01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('BURST_HOST2', 'cln-${ENVIRONMENT}-dams-brst-02.gel.zone', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Define env variable

BURST_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
BURST_DB_USER='burst'
BURST_DB_PASS='aiWu5vae'
BURST_DB_PORT='5432'
BURST_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
BURST_RBT_USER="gelprod"
BURST_RBT_PASS="QbZtKSK6qv"
BURST_RBT_PORT="5672"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare Burst Config

cd $SRC_DIR/artifacts/burst

echo "
    rabbitmq.host=$BURST_RBT_HOST
    rabbitmq.port=$BURST_RBT_PORT
    rabbitmq.user=$BURST_RBT_USER
    rabbitmq.password=$BURST_RBT_PASS
    rabbitmq.exchange=Carfax
    rabbitmq.queue=burst

    hibernate.connection.url=jdbc:postgresql://$BURST_DB_HOST:$BURST_DB_PORT/burst
    hibernate.connection.user=$BURST_DB_USER
    hibernate.connection.password=$BURST_DB_PASS

    report.email.default.subject=GeL Data Acquisition Management Report Message
    report.email.from=no-reply@mail.extge.co.uk
    report.email.host=10.1.0.3
" > config.properties

tar xf burst-service.tar

cp -fr burst*/lib lib

cp -fr burst*/bin bin

rm -rf burst*

cd .. && tar czf burst.tar.gz burst

# Deploy Burst
scp burst.tar.gz deploy@${BURST_HOST1}:/var/deploy/burst.tar.gz
scp burst.tar.gz deploy@${BURST_HOST2}:/var/deploy/burst.tar.gz
'''

        shell '''
# Update Burst1
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF

# Update Burst2
ssh deploy@${BURST_HOST1} << EOF
cd /var/deploy/

tar xzf burst.tar.gz

cd burst

sudo mv bin /opt/burst/bin.new
sudo mv lib /opt/burst/lib.new
sudo mv config.properties /opt/burst/config.properties

sudo rm -rf /opt/burst/*.old

cd /opt/burst

sudo mv bin{,.old}
sudo mv bin{.new,}
sudo mv lib{,.old}
sudo mv lib{.new,}
sudo service burst restart

#rm -rf /var/deploy/burst*

EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-frec") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('FREC_HOST', 'cln-${ENVIRONMENT}-frec01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Deploy File Receiver
scp $SRC_DIR/artifacts/file-receiver/file-receiver.tar deploy@${FREC_HOST}:/var/deploy/file-receiver.tar
'''
        shell '''
# Update File Reciever
ssh deploy@${FREC_HOST} touch /var/deploy/frecdeploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-labkey") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('LABKEY_HOST', 'cln-${ENVIRONMENT}-lk01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Copy Labkey
scp $SRC_DIR/artifacts/labkey.tar.gz deploy@${LABKEY_HOST}:/var/deploy/labkey.tar.gz
'''

        shell '''
# Upgrade Labkey
ssh deploy@${LABKEY_HOST} touch /var/deploy/labkey_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-mercury") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('MERC_HOST', 'cln-${ENVIRONMENT}-mer01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

LABKEY_HOST="cln-${ENVIRONMENT}-k01.cln.ge.local"
MERC_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
MERC_DB_PASS='Ees6aemo'
MERC_DB_PORT='5432'
MERC_DB_USER='mercury'
MERC_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
MERC_RBT_PASS='QbZtKSK6qv'
MERC_RBT_PORT='5672'
MERC_RBT_USER='gelprod'
PDS_CLIENTID='genomics_client'
PDS_CLIENTSECRET='654edc02-bd49-4c1b-8465-4de251651f89'
PDS_LOOKUP_URL="https://portal-inhealthcare-proxy.gel.zone"
PDS_PASSWORD='sUswa!8uQaku'
PDS_USERNAME='genomics_user'

cd $SRC_DIR/artifacts/mercury

# Prepare Mercury Config
echo "
# Managed by Bamboo; any changes will be overwritten on deployment.
database:
host: $MERC_DB_HOST
port: $MERC_DB_PORT
user: $MERC_DB_USER
password: $MERC_DB_PASS
auditing.name: mercury_audit
gel:
  cancer.name: mercury_gel_cancer
  rare.diseases.name: mercury_gel_rare_diseases
  common.name: mercury_gel_common
rabbitmq:
  host: $MERC_RBT_HOST
  port: $MERC_RBT_PORT
  user: $MERC_RBT_USER
  password: $MERC_RBT_PASS

burst:
  source.application.organisation: Genomics England Ltd
  strip.resource.name: true
  default:
  exception.title: MeRCURy Exception Message
  error.title: MeRCURy Error Message
  information.title: MeRCURy Information Message
pds:
  patientSearchBaseUrl: $PDS_LOOKUP_URL/api/rest/v2/pdspatientsearch
  oauthTokenUrl: $PDS_LOOKUP_URL/oauth/token
  oauthUsername: $PDS_USERNAME
  oauthPassword: $PDS_PASSWORD
  oauthClientId: $PDS_CLIENTID
  oauthClientSecret: $PDS_CLIENTSECRET
mercury:
  issue-fixing-scripts:
    root: /opt/mercury/scripts/
    scripts:
      # gmc-renames: gmcFixes.groovy
      elements: elementFixes.groovy

labkey:
  host: $LABKEY_HOST
  port: 8080
  contextPath: labkey/

dataSources:
  gel_cancer_v2-0.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_rare_diseases_v1-3.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
  gel_common.properties:
    jmxEnabled: true
    initialSize: 5
    maxActive: 50
    minIdle: 5
    maxIdle: 25
    maxWait: 10000
    maxAge: 600000
    timeBetweenEvictionRunsMillis: 5000
    minEvictableIdleTimeMillis: 60000
    validationQuery: SELECT 1
    validationQueryTimeout: 3
    validationInterval: 15000
    testOnBorrow: true
    testWhileIdle: true
    testOnReturn: false
    jdbcInterceptors: ConnectionState
    defaultTransactionIsolation: 2 # TRANSACTION_READ_COMMITTED
" > config.yml
cd .. && tar -czf mercury.tar.gz mercury

# Deploy Burst
scp mercury.tar.gz deploy@${MERC_HOST}:/var/deploy/mercury.tar.gz
'''
        shell '''
# Upgrade Mercury
ssh deploy@${MERC_HOST} << EOF

cd /var/deploy

tar xzf mercury.tar.gz

rm -rf mercury.tar.gz

cd mercury

sudo cp mercury-service.war /opt/mercury/bin/mercury.war
sudo cp config.yml /opt/mercury/config.yml
sudo cp -fruv scripts/ /opt/mercury/
sudo cp logback.groovy /opt/mercury/logback.groovy

#rm -rf /var/deploy/mercury

sudo service mercury restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-openclinica") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('OC_HOST', 'cln-${ENVIRONMENT}-oc01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
#Clean old file
ssh deploy@${OC_HOST} rm -rf /var/deploy/{openclinica.tar.gz,rarediseases-eligibility.tar.gz}

OC_DB_HOST="cln-${ENVIRONMENT}-pg84.cln.ge.local"
OC_DB_PORT="5432"
OC_MAIL_HOST="10.1.0.3"
OC_SERVICE_URL="/OCService"
OC_SERVICE_WAR="OCService.war"
OC_SYS_HOST="http://cln-prod-oc01.cln.ge.local:8080/${WEBAPP}/MainMenu"
OC_URL_PATH="rarediseases#"
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

# Prepare OC Tarball
cd $SRC_DIR/artifacts
tar -cvzf openclinica.tar.gz openclinica

#Deploy OC
scp $SRC_DIR/artifacts/openclinica.tar.gz deploy@${OC_HOST}:/var/deploy/
'''
        shell '''
# Upgrade OC
ssh deploy@${OC_HOST} touch /var/deploy/openclinica_deploy
'''
    }
    publishers {
        chucknorris()
    }
}

job("$phasedPath/deploy-sample-tracking") {
    parameters {
        //stringParam(name,value,descrioption)
        stringParam('SAMTRA_HOST', 'cln-${ENVIRONMENT}-samtra01.cln.ge.local', '')
        stringParam('ENVIRONMENT', '','')
    }
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        shell '''
SRC_DIR="/var/lib/jenkins/workspace/cln-dams/dams-build"

cd $SRC_DIR/artifacts/sampletracking

# Prepare Sample Tracking Config
SAMTRA_DB_HOST="cln-${ENVIRONMENT}-pg94.cln.ge.local"
SAMTRA_DB_PASS='aiPh6uor'
SAMTRA_DB_USER='sampletracking'
SAMTRA_DB_PORT='5432'
SAMTRA_RBT_HOST="cln-${ENVIRONMENT}-rbt01.cln.ge.local"
SAMTRA_RBT_PASS='QbZtKSK6qv'
SAMTRA_RBT_PORT='5672'
SAMTRA_RBT_USER='gelprod'
echo "
  RabbitMQHost=$SAMTRA_RBT_HOST
  RabbitMQUsername=$SAMTRA_RBT_USER
  RabbitMQPassword=$SAMTRA_RBT_PASS
  RabbitMQPort=$SAMTRA_RBT_PORT
  RabbitMQExchange=Carfax
  RabbitMQQueue=sample-tracking
  RabbitMQBurstTopic=noaudit.burst
  RabbitMQMessageOutTopic=noaudit.messages-out
  hibernate.connection.url=jdbc:postgresql://$SAMTRA_DB_HOST:$SAMTRA_DB_PORT/sampletracking
  hibernate.connection.username=$SAMTRA_DB_USER
  hibernate.connection.password=$SAMTRA_DB_PASS
  BiorepFolder=/opt/sftp-folders/biorep/GE_to_Bio
  SequencerFolder=/opt/sftp-folders/illumina/GE_to_Illu
  " > config.properties
  
cd .. && tar -czf sampletracking.tar.gz sampletracking

# Deploy Sample Tracking
scp sampletracking.tar.gz deploy@${SAMTRA_HOST}:/var/deploy/sampletracking.tar.gz
'''

        shell '''
# Update Sample Tracking
ssh deploy@${SAMTRA_HOST} << EOF

cd /var/deploy

tar xzf sampletracking.tar.gz

rm sampletracking.tar.gz

cd sampletracking

sudo cp config.properties /opt/sampletracking/config.properties

sudo cp sampletracking.jar /opt/sampletracking/bin/sampletracking.jar

#rm -rf /var/deploy/sampletracking

sudo service sampletracking restart
EOF
'''
    }
    publishers {
        chucknorris()
    }
}




