// http://stackoverflow.com/a/17147973/868173
jQuery(document).ready(function($) {
  $(".clickable-row").click(function() {
    window.document.location = "thread/" + $(this).data("message-from") + "/" + $(this).data("message-to");
  });
});
