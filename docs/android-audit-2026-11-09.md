# Android filter and account refinement

The document list now combines text search with tag, correspondent and document-type filters. Each menu has an all-items choice and a separate clear-filters action. Name lookup failures leave the returned documents available and show a warning. Metadata menus show the server's first 200 entries, matching the existing bounded lookup contract.

Android now requests `page_size=50&ordering=-added`, matching iOS and roadmap decision D7. Dates shown in Android rows are the added date. Pagination and an Android original-document viewer remain open.

HTTP400/401 from token exchange show a specific rejected-credentials message; they do not replace an existing saved account. Other HTTP failures keep their original classification. Fixture tests cover each filter individually and combined with an encoded query, the page contract, both rejected-login statuses, server errors and preservation of the old account.

Local verification passed: 11 JVM tests, Android lint (0 errors), debug APK and unsigned release AAB. Exact-commit CI repeats these gates and the unchanged iOS builds. Live Paperless-ngx and physical-device acceptance remain separate.
