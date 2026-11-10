<script setup lang="ts">
import { computed } from "vue";
import { marked } from "marked";
import DOMPurify from "dompurify";
const props = defineProps<{ text: string }>();
const renderer = new marked.Renderer();
renderer.html = () => "";
const html = computed(() =>
  DOMPurify.sanitize(
    marked.parse(props.text, {
      async: false,
      gfm: true,
      breaks: true,
      renderer,
    }) as string,
    {
      ALLOWED_TAGS: [
        "p",
        "br",
        "strong",
        "em",
        "ul",
        "ol",
        "li",
        "pre",
        "code",
        "blockquote",
        "h1",
        "h2",
        "h3",
        "h4",
        "a",
        "hr",
      ],
      ALLOWED_ATTR: ["href", "title"],
      ALLOW_DATA_ATTR: false,
    },
  ),
);
</script>
<template><div class="markdown" v-html="html" /></template>
