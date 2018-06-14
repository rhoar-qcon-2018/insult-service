pipeline {
  agent {
    label 'jenkins-slave-mvn'
  }
  stages {
    stage('Compile') {
      steps {
        sh 'mvn compile'
      }
    }
    stage('Test') {
      steps {
        sh 'mvn test'
      }
    }
    stage('OWASP Dependency Check') {
      steps {
        agent {
          label "jenkins-slave-mvn"
        }
        sh 'mvn dependency-check:check'
      }
    }
    stage('Quality Analysis') {
      steps {
        script {
          withSonarQubeEnv() {
            sh 'mvn sonar:sonar'
          }
          def qualitygate = waitForQualityGate()
          if (qualitygate.status != "OK") {
             error "Pipeline aborted due to quality gate failure: ${qualitygate.status}"
          }
        }
      }
    }
    stage('Build Image') {
      steps {
        script {
          openshift.withCluster() {
            openshift.selector('bc', 'insult-service').startBuild('--from-file=target/insult-service.jar', '--wait')
          }
        }
      }
    }
    stage('Promote to TEST') {
      steps {
        script {
          openshift.withCluster() {
            def ciProject = openshift.project()
            def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
            openshift.withProject(testProject) {
              openshift.tag('insult-service:latest', "${testProject}/insult-service:latest")
            }
          }
        }
      }
    }
    stage('Web Security Analysis') {
      steps {
        agent {
          label "jenkins-slave-zap"
        }
        script {
          def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
          sh "/zap/zap-baseline.py -r baseline.html -t http://insult-service-${testProject}.apps.qcon.openshift.opentlc.com/"
          publishHTML([
            allowMissing: false, alwaysLinkToLastBuild: false,
            keepAll: true, reportDir: '/zap/wrk', reportFiles: 'baseline.html',
            reportName: 'ZAP Baseline Scan', reportTitles: 'ZAP Baseline Scan'
          ])
        }
      }
    }
    stage('Promote to DEMO') {
      input {
        message "Promote service to DEMO environment?"
        ok "PROMOTE"
      }
      steps {
        script {
          openshift.withCluster() {
            def ciProject = openshift.project()
            def demoProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-demo/)
            openshift.withProject(demoProject) {
              openshift.tag('insult-service:latest', "${demoProject}/insult-service:latest")
            }
          }
        }
      }
    }
  }
}