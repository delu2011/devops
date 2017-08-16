String project        = 'cln'
String app            = 'ddf'

String basePath       = "${project}-${app}"
String phasedPath     = "$basePath/phased"

String ddfSSHHost     = '10.16.128.68'
String ddfSSHUser     = 'root'
String ddfSSHCMD      = "/usr/bin/ssh -l ${ddfSSHUser} ${ddfSSHHost}"

folder(basePath) {
    description "${project} - ${app} Related Builds"
}

folder("$basePath/phased") {
    description "${project} - ${app} Phased Builds"
}

String[] etl_steps = []
Map etl_long = [:]

etl_steps = ['apic', 'dc', 'cd', 'dm', 'de', 'cds', 're', 'cds2', 'dd', 'apic']
etl_long = [apic: 'api-caller', dc: 'data-capture', cd: 'data-cleaning', dm: 'data-modelling', de: 'delta-engine', cds: 'central-data-store', re: 'rules-engine', cds2: 'central-data-store-cdm', dd: 'data-delivery']

etl_steps.each { etl_step ->
    job("$phasedPath/phentaho-etl-${etl_long.get(etl_step)}") {
        parameters {
            stringParam('SSH', ddfSSHCMD)
            stringParam('PATH', '/pentaho/data-integration')
            stringParam('CMD', '${PATH}/kitchen.sh')
            stringParam('ETL', etl_step, etl_long.get(etl_step))
            if ( etl_step == 'cds2') {
                stringParam('FILE', '${PATH}/geldf-cds-kettle-etl/ETL/cds-etl-publish-cdm.kjb','')
            } else {
                stringParam('FILE', '${PATH}/geldf-${ETL}-kettle-etl/ETL/${ETL}-etl-refresh.kjb','')
            }

        }
        wrappers {
            colorizeOutput()
            preBuildCleanup()
        }
        steps {
            shell '${SSH} ${CMD} -file="${FILE}"'
        }
        publishers {
            chucknorris()
        }
    }
}

multiJob("$basePath/${app.toLowerCase()}-etl-execute") {
    wrappers {
        colorizeOutput()
        preBuildCleanup()
    }
    steps {
        phase('api-caller-seed') {
            phaseJob('cln-ddf/phased/phentaho-etl-api-caller')
        }
        phase('data-capture') {
            phaseJob('cln-ddf/phased/phentaho-etl-data-capture')
        }
        phase('data-cleaning') {
            phaseJob('cln-ddf/phased/phentaho-etl-data-cleaning')
        }
        phase('data-modelling') {
            phaseJob('cln-ddf/phased/phentaho-etl-data-modelling')
        }
        phase('delta-engine') {
            phaseJob('cln-ddf/phased/phentaho-etl-delta-engine')
        }
        phase('central-data-store') {
            phaseJob('cln-ddf/phased/phentaho-etl-central-data-store')
        }
        phase('rules-engine') {
            phaseJob('cln-ddf/phased/phentaho-etl-rules-engine')
        }
        phase('central-data-store-cdm') {
            phaseJob('cln-ddf/phased/phentaho-etl-central-data-store-cdm')
        }
        phase('data-delivery') {
            phaseJob('cln-ddf/phased/phentaho-etl-data-delivery')
        }
        phase('api-caller-execute') {
            phaseJob('cln-ddf/phased/phentaho-etl-api-caller')
        }
    }
    publishers {
        chucknorris()
    }
}
