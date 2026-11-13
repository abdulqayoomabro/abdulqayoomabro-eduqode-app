# H5P for instructors (from the Tutor LMS dashboard)

The H5P plugin lets only people who can open wp-admin make H5P content, and Tutor LMS Pro can close wp-admin for instructors. The EduQode App Connector adds **Dashboard → Interactive exercises (H5P)** for approved Tutor LMS instructors.

| They can | They cannot |
|---|---|
| see the exercises they made, with the code for lessons (`[h5p id="12"]`) and where each is used | see or edit other people's exercises (an admin sees all) |
| make new exercises and edit theirs with the real H5P editor, shown inside the dashboard | install or update content types (only the admin: H5P Content → Libraries) |
| delete their exercises that no lesson or quiz uses | delete an exercise that is still in a lesson or quiz |

Using an exercise: paste its code into the lesson text, or add a question of type **H5P** in the course builder and choose it. Exercises also work offline in the app.

Admin: EduQode App → General → *H5P for instructors* (on by default). Install the content types instructors should use first (H5P Content → Add New → Get, or upload).

How it works: instructors get the H5P plugin's `edit_h5p_contents`, `view_h5p_contents` and `view_h5p_results` rights (not `edit_others_h5p_contents`, `manage_h5p_libraries`, `install_recommended_h5p_libraries` or `disable_h5p_security`). The dashboard page opens the H5P plugin's own *Add New* page in a frame without the wp-admin menus; Tutor LMS Pro's wp-admin block is lifted only for that page.
