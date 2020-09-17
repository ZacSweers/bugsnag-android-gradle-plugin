Feature: Manual invocation of upload task

Scenario: Manually invoking upload task sends requests
    When I build "default_app" using the "all_disabled" bugsnag config
    And I run the script "features/scripts/manual_upload.sh" synchronously
    And I wait to receive 2 requests

    Then 1 requests are valid for the build API and match the following:
      | appVersionCode |
      | 1              |

    And 1 requests are valid for the android mapping API and match the following:
      | versionCode |
      | 1           |
